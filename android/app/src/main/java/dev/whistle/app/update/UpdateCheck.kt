package dev.whistle.app.update

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Update check: asks GitHub (api.github.com only, no auth) for the latest release at most once a
 * day and, when it is newer than this build, offers a link to its APK. Nothing is downloaded or
 * installed by the app; the link opens in the browser through the link sheet.
 */
object UpdateConfig {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/froid1911/whistle/releases/latest"
    const val RELEASES_PAGE = "https://github.com/froid1911/whistle/releases/latest"
    /** Download links are only taken from the release when they point into the project's releases. */
    const val RELEASE_PREFIX = "https://github.com/froid1911/whistle/releases/"
    const val INTERVAL_MS = 24L * 60 * 60 * 1000
}

/** Semantic version compare (major.minor.patch, optional leading "v" and -prerelease/+build). */
object Versions {
    private val re = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    data class Parsed(val core: List<Int>, val pre: String?)

    fun parse(v: String): Parsed? {
        val m = re.matchEntire(v.trim()) ?: return null
        val core = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { "0" }).map {
            it.toIntOrNull() ?: return null
        }
        return Parsed(core, m.groupValues[4].ifEmpty { null })
    }

    /** < 0, 0, > 0 like compareTo; null when either is not a version. */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0..2) if (x.core[i] != y.core[i]) return x.core[i].compareTo(y.core[i])
        return when {
            x.pre == y.pre -> 0
            x.pre == null -> 1 // 1.0.0 > 1.0.0-rc1
            y.pre == null -> -1
            else -> comparePre(x.pre, y.pre)
        }
    }

    private fun comparePre(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until minOf(pa.size, pb.size)) {
            val na = pa[i].toIntOrNull()
            val nb = pb[i].toIntOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> pa[i].compareTo(pb[i])
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    /** True only when [candidate] is a valid version strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = (compare(candidate, current) ?: 0) > 0
}

/** At most one check per [UpdateConfig.INTERVAL_MS]; a clock that went backwards allows one. */
object UpdateThrottle {
    fun due(nowMs: Long, lastCheckMs: Long, intervalMs: Long = UpdateConfig.INTERVAL_MS): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs
}

/** A newer release: its version (without "v") and where to download it. */
data class AvailableUpdate(val version: String, val url: String)

/** The latest release as GitHub reports it. */
data class LatestRelease(val version: String, val downloadUrl: String)

/** Outcome of asking GitHub. */
sealed interface FetchResult {
    data class Ok(val release: LatestRelease) : FetchResult
    /** GitHub answered but not with a release (rate limit, no release yet, odd JSON). */
    data object Failed : FetchResult
    /** No network: does not count as a check. */
    data object Offline : FetchResult
}

/** Where the checker keeps its state (SharedPreferences in the app, a map in tests). */
interface UpdateStore {
    var enabled: Boolean
    var lastCheckMs: Long
    var latestVersion: String?
    var latestUrl: String?
    var dismissedVersion: String?
}

class PrefsUpdateStore(private val prefs: SharedPreferences) : UpdateStore {
    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
    override var lastCheckMs: Long
        get() = prefs.getLong("lastCheck", 0L)
        set(v) = prefs.edit().putLong("lastCheck", v).apply()
    override var latestVersion: String?
        get() = prefs.getString("latestVersion", null)
        set(v) = prefs.edit().putString("latestVersion", v).apply()
    override var latestUrl: String?
        get() = prefs.getString("latestUrl", null)
        set(v) = prefs.edit().putString("latestUrl", v).apply()
    override var dismissedVersion: String?
        get() = prefs.getString("dismissed", null)
        set(v) = prefs.edit().putString("dismissed", v).apply()
}

class MemoryUpdateStore : UpdateStore {
    override var enabled = true
    override var lastCheckMs = 0L
    override var latestVersion: String? = null
    override var latestUrl: String? = null
    override var dismissedVersion: String? = null
}

/** GET /repos/froid1911/whistle/releases/latest, unauthenticated. */
class GitHubReleases(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val url: String = UpdateConfig.LATEST_RELEASE_API,
) {
    @Serializable private data class Asset(val name: String = "", @SerialName("browser_download_url") val url: String = "")

    @Serializable private data class Release(
        @SerialName("tag_name") val tag: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): FetchResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext FetchResult.Failed
                val body = resp.body.string()
                if (body.length > MAX_BODY) return@withContext FetchResult.Failed
                val r = runCatching { json.decodeFromString(Release.serializer(), body) }.getOrNull()
                    ?: return@withContext FetchResult.Failed
                parse(r)?.let { FetchResult.Ok(it) } ?: FetchResult.Failed
            }
        } catch (e: IOException) {
            FetchResult.Offline
        }
    }

    private fun parse(r: Release): LatestRelease? {
        if (r.draft || r.prerelease) return null
        val version = r.tag.removePrefix("v").removePrefix("V")
        if (Versions.parse(version) == null) return null
        val apk = r.assets.firstOrNull { it.name == "whistle-$version.apk" }
            ?: r.assets.firstOrNull { it.name.endsWith(".apk") }
        val url = listOf(apk?.url, r.htmlUrl).firstOrNull { it != null && it.startsWith(UpdateConfig.RELEASE_PREFIX) }
            ?: UpdateConfig.RELEASES_PAGE
        return LatestRelease(version, url)
    }

    private companion object {
        const val MAX_BODY = 1_000_000
    }
}

/**
 * Remembers the latest release seen and decides whether to offer it. [checkIfDue] is called on
 * every app start; it asks GitHub only when enabled and the last check is a day old.
 */
class UpdateChecker(
    private val store: UpdateStore,
    private val currentVersion: String,
    private val clock: () -> Long,
    private val fetch: suspend () -> FetchResult,
) {
    private val mutex = Mutex()
    private val _enabled = MutableStateFlow(store.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _available = MutableStateFlow(compute())
    /** A newer release to offer, or null (none, disabled, or dismissed with "Later"). */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private fun compute(): AvailableUpdate? {
        if (!store.enabled) return null
        val v = store.latestVersion ?: return null
        val url = store.latestUrl ?: return null
        if (!Versions.isNewer(v, currentVersion) || v == store.dismissedVersion) return null
        return AvailableUpdate(v, url)
    }

    fun setEnabled(on: Boolean) {
        store.enabled = on
        _enabled.value = on
        _available.value = compute()
    }

    /** "Later": hide this version; a newer release shows the card again. */
    fun dismiss() {
        store.dismissedVersion = _available.value?.version ?: return
        _available.value = compute()
    }

    /** Asks GitHub when due; returns true when a check was made. Never throws. */
    suspend fun checkIfDue(): Boolean = mutex.withLock {
        if (!store.enabled || !UpdateThrottle.due(clock(), store.lastCheckMs)) return false
        val result = runCatching { fetch() }.getOrDefault(FetchResult.Failed)
        if (result == FetchResult.Offline) return false // try again on the next start
        store.lastCheckMs = clock()
        if (result is FetchResult.Ok) {
            store.latestVersion = result.release.version
            store.latestUrl = result.release.downloadUrl
            _available.value = compute()
        }
        true
    }
}
