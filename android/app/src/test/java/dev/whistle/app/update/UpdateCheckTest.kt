package dev.whistle.app.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun compares() {
        assertTrue(Versions.isNewer("0.6.0", "0.5.1"))
        assertTrue(Versions.isNewer("v0.6.0", "0.5.1"))
        assertTrue(Versions.isNewer("0.10.0", "0.9.9"))
        assertTrue(Versions.isNewer("1.0.0", "0.99.99"))
        assertTrue(Versions.isNewer("0.6.1", "0.6.0"))
        assertTrue(Versions.isNewer("0.6.0", "0.6.0-rc.1"))
        assertTrue(Versions.isNewer("0.6.0-rc.2", "0.6.0-rc.1"))
        assertTrue(Versions.isNewer("0.6.0-rc.10", "0.6.0-rc.9"))
        assertTrue(Versions.isNewer("0.7", "0.6.9"))
        assertFalse(Versions.isNewer("0.6.0", "0.6.0"))
        assertFalse(Versions.isNewer("0.6.0+build.5", "0.6.0"))
        assertFalse(Versions.isNewer("0.5.9", "0.6.0"))
        assertFalse(Versions.isNewer("0.6.0-rc.1", "0.6.0"))
    }

    @Test fun garbageIsNeverNewer() {
        assertFalse(Versions.isNewer("latest", "0.6.0"))
        assertFalse(Versions.isNewer("", "0.6.0"))
        assertFalse(Versions.isNewer("1.0.0", "dev"))
        assertFalse(Versions.isNewer("99999999999.0.0", "0.6.0"))
        assertNull(Versions.compare("1.0.0.0", "1.0.0"))
    }
}

class UpdateThrottleTest {
    private val day = UpdateConfig.INTERVAL_MS

    @Test fun oncePerDay() {
        assertTrue(UpdateThrottle.due(nowMs = 1_000, lastCheckMs = 0))
        assertFalse(UpdateThrottle.due(nowMs = 1_000 + day - 1, lastCheckMs = 1_000))
        assertTrue(UpdateThrottle.due(nowMs = 1_000 + day, lastCheckMs = 1_000))
        assertTrue(UpdateThrottle.due(nowMs = 500, lastCheckMs = 1_000)) // clock went back
    }
}

class UpdateCheckerTest {
    private var now = 10_000_000L
    private var calls = 0
    private var next: FetchResult = FetchResult.Ok(LatestRelease("0.7.0", "https://github.com/froid1911/whistle/releases/download/v0.7.0/whistle-0.7.0.apk"))
    private val store = MemoryUpdateStore()
    private fun checker(current: String = "0.6.0") = UpdateChecker(store, current, { now }) { calls++; next }

    @Test fun offersNewerRelease() = runTest {
        val c = checker()
        assertNull(c.available.value)
        assertTrue(c.checkIfDue())
        assertEquals(AvailableUpdate("0.7.0", "https://github.com/froid1911/whistle/releases/download/v0.7.0/whistle-0.7.0.apk"), c.available.value)
        // Remembered across restarts without asking again.
        assertEquals("0.7.0", checker().available.value?.version)
        assertNull(checker(current = "0.7.0").available.value)
    }

    @Test fun throttledToOncePerDay() = runTest {
        val c = checker()
        assertTrue(c.checkIfDue())
        now += UpdateConfig.INTERVAL_MS / 2
        assertFalse(c.checkIfDue())
        assertFalse(checker().checkIfDue())
        now += UpdateConfig.INTERVAL_MS / 2
        assertTrue(c.checkIfDue())
        assertEquals(2, calls)
    }

    @Test fun failuresCountOfflineDoesNot() = runTest {
        val c = checker()
        next = FetchResult.Offline
        assertFalse(c.checkIfDue())
        assertEquals(0L, store.lastCheckMs)
        next = FetchResult.Failed // e.g. rate limited
        assertTrue(c.checkIfDue())
        assertFalse(c.checkIfDue())
        assertNull(c.available.value)
    }

    @Test fun laterHidesThisVersionOnly() = runTest {
        val c = checker()
        c.checkIfDue()
        c.dismiss()
        assertNull(c.available.value)
        assertNull(checker().available.value)
        now += UpdateConfig.INTERVAL_MS
        next = FetchResult.Ok(LatestRelease("0.8.0", UpdateConfig.RELEASES_PAGE))
        c.checkIfDue()
        assertEquals("0.8.0", c.available.value?.version)
    }

    @Test fun disabledNeverAsks() = runTest {
        val c = checker()
        c.checkIfDue()
        c.setEnabled(false)
        assertNull(c.available.value)
        now += 2 * UpdateConfig.INTERVAL_MS
        assertFalse(c.checkIfDue())
        assertEquals(1, calls)
        c.setEnabled(true)
        assertEquals("0.7.0", c.available.value?.version)
    }
}

class GitHubReleasesTest {
    private fun fetch(code: Int, body: String): FetchResult {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
        server.start()
        try {
            return kotlinx.coroutines.runBlocking {
                GitHubReleases(OkHttpClient(), "test", server.url("/latest").toString()).latest()
            }
        } finally {
            server.close()
        }
    }

    @Test fun picksTheApk() {
        val r = fetch(
            200,
            """{"tag_name":"v0.7.0","html_url":"https://github.com/froid1911/whistle/releases/tag/v0.7.0","extra":1,
               "assets":[{"name":"SHA256SUMS","browser_download_url":"https://github.com/froid1911/whistle/releases/download/v0.7.0/SHA256SUMS"},
                         {"name":"whistle-0.7.0.apk","browser_download_url":"https://github.com/froid1911/whistle/releases/download/v0.7.0/whistle-0.7.0.apk"}]}""",
        )
        assertEquals(
            FetchResult.Ok(LatestRelease("0.7.0", "https://github.com/froid1911/whistle/releases/download/v0.7.0/whistle-0.7.0.apk")),
            r,
        )
    }

    @Test fun foreignLinksFallBackToReleasesPage() {
        val r = fetch(
            200,
            """{"tag_name":"v0.7.0","html_url":"https://evil.example/x",
               "assets":[{"name":"whistle-0.7.0.apk","browser_download_url":"https://evil.example/whistle.apk"}]}""",
        )
        assertEquals(FetchResult.Ok(LatestRelease("0.7.0", UpdateConfig.RELEASES_PAGE)), r)
    }

    @Test fun rateLimitAndJunkFail() {
        assertEquals(FetchResult.Failed, fetch(403, """{"message":"API rate limit exceeded"}"""))
        assertEquals(FetchResult.Failed, fetch(200, "not json"))
        assertEquals(FetchResult.Failed, fetch(200, """{"tag_name":"nightly"}"""))
        assertEquals(FetchResult.Failed, fetch(200, """{"tag_name":"v1.0.0","prerelease":true}"""))
    }

    @Test fun offline() {
        val r = kotlinx.coroutines.runBlocking { GitHubReleases(OkHttpClient(), "test", "http://127.0.0.1:1/latest").latest() }
        assertEquals(FetchResult.Offline, r)
    }
}
