package dev.whistle.app.ui.chat

import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where drafts survive process death (SharedPreferences in the app, a map in tests). */
interface DraftBacking {
    fun loadAll(): Map<Pair<String, String>, String>

    /** Saves the draft; blank removes it. */
    fun save(machineId: String, paneId: String, text: String)
}

class PrefsDraftBacking(private val prefs: SharedPreferences) : DraftBacking {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    override fun loadAll(): Map<Pair<String, String>, String> = buildMap {
        for ((k, v) in prefs.all) {
            if (!k.startsWith(PREFIX) || v !is String) continue
            val parts = k.removePrefix(PREFIX).split('|')
            if (parts.size == 2) put(dec(parts[0]) to dec(parts[1]), v)
        }
    }

    override fun save(machineId: String, paneId: String, text: String) {
        val key = PREFIX + enc(machineId) + "|" + enc(paneId)
        prefs.edit().apply { if (text.isEmpty()) remove(key) else putString(key, text) }.apply()
    }

    private companion object { const val PREFIX = "draft:" }
}

/**
 * App-scope composer drafts per (machine, pane). The chat composer reads and writes its text here
 * (so it survives leaving the page); Share and Changes append to it. Cleared when sent.
 * With a [backing] (+ [scope]) drafts are written through, debounced by [debounceMs]; clears
 * are written at once, [flush] writes everything pending.
 */
class DraftStore(
    private val backing: DraftBacking? = null,
    private val scope: CoroutineScope? = null,
    private val debounceMs: Long = 300,
) {
    private val flows = HashMap<Pair<String, String>, MutableStateFlow<String>>()
    private val jobs = HashMap<Pair<String, String>, Job>()

    init {
        backing?.loadAll()?.forEach { (k, v) -> flows[k] = MutableStateFlow(v) }
    }

    @Synchronized
    private fun flow(m: String, p: String) = flows.getOrPut(m to p) { MutableStateFlow("") }

    fun draft(machineId: String, paneId: String): StateFlow<String> = flow(machineId, paneId).asStateFlow()

    fun get(machineId: String, paneId: String): String = flow(machineId, paneId).value

    fun set(machineId: String, paneId: String, text: String) {
        flow(machineId, paneId).value = text
        persist(machineId, paneId, immediate = text.isEmpty())
    }

    /** Adds [text] on a line of its own after what is already there. */
    fun append(machineId: String, paneId: String, text: String) {
        if (text.isEmpty()) return
        flow(machineId, paneId).update { joinLines(it, text) }
        persist(machineId, paneId, immediate = false)
    }

    fun clear(machineId: String, paneId: String) = set(machineId, paneId, "")

    /** Atomically returns the draft and empties it: an append racing a send is never lost. */
    fun takeAndClear(machineId: String, paneId: String): String {
        val f = flow(machineId, paneId)
        while (true) {
            val cur = f.value
            if (f.compareAndSet(cur, "")) {
                persist(machineId, paneId, immediate = true)
                return cur
            }
        }
    }

    /** Writes every draft to the backing store now. */
    fun flush() {
        val b = backing ?: return
        val keys = synchronized(this) { jobs.values.forEach { it.cancel() }; jobs.clear(); flows.keys.toList() }
        keys.forEach { (m, p) -> b.save(m, p, get(m, p)) }
    }

    private fun persist(m: String, p: String, immediate: Boolean) {
        val b = backing ?: return
        val key = m to p
        synchronized(this) { jobs.remove(key)?.cancel() }
        if (immediate || scope == null) {
            b.save(m, p, get(m, p))
            return
        }
        val job = scope.launch {
            delay(debounceMs)
            b.save(m, p, get(m, p))
        }
        synchronized(this) { jobs[key] = job }
    }

    companion object {
        fun joinLines(base: String, add: String): String =
            if (base.isEmpty()) add else base.trimEnd('\n') + "\n" + add
    }
}

private inline fun <T> MutableStateFlow<T>.update(f: (T) -> T) {
    while (true) {
        val cur = value
        if (compareAndSet(cur, f(cur))) return
    }
}

/** One pane's view of the [DraftStore]. */
class PaneDraft(private val store: DraftStore, val machineId: String, val paneId: String) {
    val text: StateFlow<String> get() = store.draft(machineId, paneId)
    fun set(t: String) = store.set(machineId, paneId, t)
    fun clear() = store.clear(machineId, paneId)
    fun append(t: String) = store.append(machineId, paneId, t)
    fun takeAndClear(): String = store.takeAndClear(machineId, paneId)
}
