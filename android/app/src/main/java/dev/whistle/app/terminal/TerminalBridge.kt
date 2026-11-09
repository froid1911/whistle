package dev.whistle.app.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.whistle.app.protocol.TermFrame
import kotlin.math.abs

/**
 * The xterm.js page (assets/xterm/index.html) in a WebView, and the calls between it and Kotlin.
 *
 * Kotlin → JS: frames are queued in a [FrameQueue] (coalesced at full frames, capped) and handed
 * over once per animation frame as base64 (the page decodes them to bytes; no string re-encoding
 * of ANSI here) and written in one batch.
 * JS → Kotlin: typed data ([onData]), the fit size ([onFit]), DECCKM changes ([appCursor]), the
 * applied font size.
 *
 * Pinch zoom changes xterm's fontSize ([FontZoom], clamped 6–20 px) instead of scaling the page:
 * the terminal stays at the pane's cols×rows and the WebView only pans it. The size is sent at
 * most once per animation frame (the page additionally throttles re-layouts to one per 60 ms).
 *
 * Lost frames (queue overflow, or frames arriving while the view is detached, when
 * postOnAnimation never runs) call [onResync]: the owner reopens the session for a fresh full frame.
 * A crashed/killed renderer is handled ([onGone]) instead of taking the app down; the bridge is
 * then dead and the owner creates a new one.
 * Callbacks run on the main thread. Main thread only.
 */
class TerminalBridge(context: Context) {
    var onData: (String) -> Unit = {}
    var onFit: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    var onResync: () -> Unit = {}
    var onGone: () -> Unit = {}

    /** The user's pinched font size (px); set before the page is ready to restore a pane's size. */
    var userFontSize: Float? = null
        set(value) {
            field = value?.let { FontZoom.clamp(it) }
            field?.let { sendFont(it) }
        }

    /** Called with each new pinched font size (to remember it for the pane). */
    var onFontSize: (Float) -> Unit = {}

    /** The font size the page currently renders with. */
    private var currentFont = 13f
    private var fontScheduled = false

    /** The terminal's application-cursor-keys mode (arrows send ESC O x). */
    var appCursor by mutableStateOf(false)
        private set

    private var ready = false
    private var scheduled = false
    private var destroyed = false
    /** Detached after having been attached (pager page off screen): nothing drains. */
    private var detached = false

    /** Frames were dropped while detached: resync when attached again. */
    private var missed = false
    private val queue = FrameQueue()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    val webView: WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            // Only the bundled page: no file/content access beyond assets, no network at all.
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            // Pane-size view: the page pans; pinch is ours (font size), never a page zoom.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
        }
        setBackgroundColor(0xFF0F1412.toInt()) // Night pasture "inset"
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true // never navigate

            // Without this, a renderer crash or OOM kill takes the whole app down.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "terminal renderer gone (crash=${detail.didCrash()})")
                destroy()
                onGone()
                return true
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                detached = false
                if (missed) {
                    missed = false
                    onResync()
                }
                schedule()
            }

            override fun onViewDetachedFromWindow(v: View) {
                detached = true
                scheduled = false // a pending postOnAnimation runs only on reattach
                if (!queue.isEmpty) missed = true
                queue.clear()
            }
        })
        addJavascriptInterface(JsApi(), "HerdrTerm")
        setOnTouchListener(PanGuard(context))
        loadUrl(PAGE)
    }

    /**
     * Queues [frame]. The page clears the screen before the first frame of a new session ([fresh])
     * and before any full frame (e.g. the bridge's observe→control restart keeps the sid).
     */
    fun frame(frame: TermFrame, fresh: Boolean) {
        if (destroyed) return
        if (detached) {
            // Nothing drains while detached: drop, and resync on reattach.
            missed = true
            return
        }
        when (queue.add(frame.bytes, frame.width, frame.height, clear = fresh || frame.full)) {
            FrameQueue.Added.Queued -> schedule()
            FrameQueue.Added.Overflow -> {
                Log.w(TAG, "terminal frame queue overflow, resyncing")
                onResync()
            }
            FrameQueue.Added.Invalid -> Log.w(TAG, "dropped terminal frame with non-base64 bytes")
        }
    }

    /** Switches to fit layout and reports the fitting size via [onFit]. */
    fun proposeFit() = js("herdr.proposeFit()")

    fun setFitLayout(on: Boolean) = js("herdr.setFit($on)")

    /** Focuses the terminal (shows the soft keyboard). */
    fun focus() {
        webView.requestFocus()
        js("herdr.focus()")
    }

    /** Idempotent; also called when the renderer is gone. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        ready = false
        queue.clear()
        onData = {}
        onFit = { _, _ -> }
        onFontSize = {}
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface("HerdrTerm")
        webView.destroy()
    }

    private fun js(code: String) {
        if (ready && !destroyed) webView.evaluateJavascript(code, null)
    }

    /** Hands the latest font size to the page on the next animation frame (coalesced). */
    private fun sendFont(px: Float) {
        if (!ready || destroyed || fontScheduled) return
        fontScheduled = true
        webView.postOnAnimation {
            fontScheduled = false
            val fs = userFontSize ?: return@postOnAnimation
            if (ready && !destroyed) webView.evaluateJavascript("herdr.setFontSize($fs)", null)
        }
    }

    private fun schedule() {
        if (!ready || destroyed || detached || scheduled || queue.isEmpty) return
        scheduled = true
        webView.postOnAnimation {
            scheduled = false
            if (!ready || destroyed) return@postOnAnimation
            val batch = queue.drain() ?: return@postOnAnimation
            webView.evaluateJavascript("herdr.frames([$batch])", null)
        }
    }

    /** Called by the page on a binder thread; everything is posted to the main thread. */
    private inner class JsApi {
        @JavascriptInterface fun onReady() = post {
            ready = true
            userFontSize?.let { sendFont(it) }
            schedule()
        }

        @JavascriptInterface fun onFont(px: Float) = post { if (px.isFinite()) currentFont = px }

        @JavascriptInterface fun onData(s: String) = post { onData.invoke(s) }

        @JavascriptInterface fun onFit(cols: Int, rows: Int) = post { onFit.invoke(cols, rows) }

        @JavascriptInterface fun onMode(app: Boolean) = post { appCursor = app }

        private fun post(block: () -> Unit) {
            webView.post { if (!destroyed) block() }
        }
    }

    /**
     * Keeps a horizontal pan or a pinch inside the WebView instead of swiping the pane pager:
     * claims the gesture when the page can still scroll the way the finger moves (or on 2 fingers).
     * A pinch is consumed here (the WebView gets a cancel) and turned into a font size.
     */
    private inner class PanGuard(context: Context) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var pinching = false
        private var pinchBase = 13f
        private var pinchScale = 1f

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchBase = userFontSize ?: currentFont
                pinchScale = 1f
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchScale *= d.scaleFactor
                val px = FontZoom.scaled(pinchBase, pinchScale)
                if (px != userFontSize) {
                    userFontSize = px
                    onFontSize(px)
                }
                return true
            }
        }).apply { isQuickScaleEnabled = false }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; pinching = false }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    if (!pinching) {
                        pinching = true
                        // The page saw the first finger: end its gesture before we take over.
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        v.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!pinching) {
                    val dx = e.x - downX
                    if (abs(dx) > abs(e.y - downY) && v.canScrollHorizontally(if (dx < 0) 1 else -1)) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pinching) {
                    pinching = false
                    return true
                }
            }
            return pinching // while pinching the WebView sees nothing (no page zoom/scroll)
        }
    }

    private companion object {
        const val PAGE = "file:///android_asset/xterm/index.html"
        const val TAG = "TerminalBridge"
    }
}
