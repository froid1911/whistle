package dev.whistle.app.terminal

/**
 * Frames waiting to be handed to the page, as JS array entries `['b64',w,h,clear]`. Pure Kotlin.
 *
 * - A clear (full / new-session) frame redraws the whole screen, so everything queued before it is
 *   dropped: after a stall only the latest redraw and its diffs are parsed.
 * - The queue is capped at [capChars]; on overflow it is emptied and [Added.Overflow] tells the
 *   owner to resync (reopen the session for a fresh full frame).
 * - Bytes are interpolated into JS source, so anything that is not plain base64 is rejected.
 */
class FrameQueue(private val capChars: Int = DEFAULT_CAP) {
    enum class Added { Queued, Overflow, Invalid }

    private val entries = ArrayDeque<String>()
    private var chars = 0

    val isEmpty: Boolean get() = entries.isEmpty()

    fun add(b64: String, width: Int, height: Int, clear: Boolean): Added {
        if (!BASE64.matches(b64)) return Added.Invalid
        if (clear) this.clear()
        val entry = "['$b64',$width,$height,$clear]"
        if (chars + entry.length > capChars) {
            this.clear()
            return Added.Overflow
        }
        entries.addLast(entry)
        chars += entry.length + 1
        return Added.Queued
    }

    /** The queued entries joined for `herdr.frames([...])`, or null when empty; empties the queue. */
    fun drain(): String? {
        if (entries.isEmpty()) return null
        return entries.joinToString(",").also { clear() }
    }

    fun clear() {
        entries.clear()
        chars = 0
    }

    companion object {
        const val DEFAULT_CAP = 2 * 1024 * 1024
        private val BASE64 = Regex("^[A-Za-z0-9+/=]*$")
    }
}
