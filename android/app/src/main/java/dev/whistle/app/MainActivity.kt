package dev.whistle.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import dev.whistle.app.ui.PaneTarget
import androidx.fragment.app.FragmentActivity
import dev.whistle.app.security.ActivityLock
import dev.whistle.app.security.LockGate
import dev.whistle.app.ui.links.SafeLinks
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.whistle.app.ui.HerdrNavHost
import dev.whistle.app.ui.HerdrTheme

/** Single activity. Screens read the Room cache directly; connections live while it is started. */
class MainActivity : FragmentActivity() {
    companion object {
        const val EXTRA_MACHINE_ID = "dev.whistle.app.MACHINE_ID"
        const val EXTRA_PANE_ID = "dev.whistle.app.PANE_ID"

        /*
         * NEW_TASK matters when launched from ShareActivity (empty taskAffinity, own task): without it
         * CLEAR_TOP/SINGLE_TOP only look in the caller's task and a second MainActivity would start.
         * With it (and launchMode singleTask in the manifest) the running herdr task is resumed and
         * onNewIntent navigates. Same for any other caller (Changes, notifications).
         */
        /** Intent that opens [paneId]'s chat on [machineId] (draft stays unsent). */
        fun openPane(context: Context, machineId: String, paneId: String) =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_MACHINE_ID, machineId).putExtra(EXTRA_PANE_ID, paneId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private const val MAX_PAIR_LINK = 2048

        /**
         * The `whistle://pair?…` link of a VIEW intent (system camera QR, a tapped link), or null.
         * The activity is exported: anything else is ignored, and the link itself is only text for
         * the pairing screen's parser.
         */
        fun pairLink(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val data = intent.data ?: return null
            if (!data.scheme.equals("whistle", ignoreCase = true) || !data.host.equals("pair", ignoreCase = true)) return null
            return data.toString().takeIf { it.length <= MAX_PAIR_LINK }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lockGate = ActivityLock(this, container.appLock)
        if (savedInstanceState == null) handle(intent)
        setContent {
            HerdrTheme {
                // Locked: only the lock screen is composed; deep links wait here and navigate after unlock.
                LockGate(lockGate) {
                    SafeLinks {
                        HerdrNavHost(
                            container,
                            openRequest = openRequest.value, onOpenHandled = { openRequest.value = null },
                            pairRequest = pairRequest.value, onPairHandled = { pairRequest.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private val openRequest = mutableStateOf<PaneTarget?>(null)
    private val pairRequest = mutableStateOf<String?>(null)
    private lateinit var lockGate: ActivityLock

    private fun handle(intent: Intent?) {
        pairLink(intent)?.let {
            pairRequest.value = it
            intent?.data = null // not again after a recreate
            return
        }
        val m = intent?.getStringExtra(EXTRA_MACHINE_ID) ?: return
        val p = intent.getStringExtra(EXTRA_PANE_ID) ?: return
        openRequest.value = PaneTarget(m, p)
        intent.removeExtra(EXTRA_PANE_ID) // not again after a recreate
    }

    override fun onStart() {
        super.onStart()
        lockGate.onStart()
        container.enterForeground()
        container.connectAll() // background: the cache is already on screen
    }

    override fun onStop() {
        super.onStop()
        lockGate.onStop() // "Immediately" means now, not after ProcessLifecycleOwner's 700 ms
        container.exitForeground() // balanced with onStart, rotation included
        // A rotation restarts at once: keep the sockets instead of reconnecting.
        if (!isChangingConfigurations) {
            container.disconnectAll()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lockGate.onDestroy()
    }
}
