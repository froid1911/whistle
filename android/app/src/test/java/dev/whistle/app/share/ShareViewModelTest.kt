package dev.whistle.app.share

import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.Machine
import dev.whistle.app.protocol.Uploader
import dev.whistle.app.ui.chat.DraftStore
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShareViewModelTest {
    private lateinit var server: MockWebServer
    private val drafts = DraftStore()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.close() }

    private fun pane(id: String = "w1:p1") =
        PaneUi(id, "t1", "w1", "claude", "/x", "agent", "claude", AgentStatus.IDLE, "claude:s", true, null, null, null, null)

    private fun target() = SharePane("m1", "myserver", pane())

    private fun vm(input: ShareInput, base: String = "http://127.0.0.1:${server.port}") = ShareViewModel(
        input,
        object : ShareBackend {
            override fun agentPanes() = flowOf(listOf(target()))
            override fun machine(id: String) = Machine(id, "myserver", base)
        },
        Uploader(OkHttpClient()),
        drafts,
        scope,
    )

    private fun img(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3), size: Long = bytes.size.toLong()) =
        SharedImage(name, "image/png", size) { ByteArrayInputStream(bytes) }

    private fun await(v: ShareViewModel) = runBlocking { withTimeout(5_000) { v.state.first { !it.busy } } }

    @Test fun sharedTextPrefillsDraft() {
        val v = vm(ShareInput("https://example.com/a", emptyList()))
        v.choose(target())
        val s = await(v)
        assertEquals("https://example.com/a", drafts.get("m1", "w1:p1"))
        assertEquals(target(), s.opened)
        assertEquals(0, server.requestCount)
    }

    @Test fun sharedTextIsAppendedToExistingDraft() {
        drafts.set("m1", "w1:p1", "look at")
        vm(ShareInput("https://x", emptyList())).choose(target())
        assertEquals("look at\nhttps://x", drafts.get("m1", "w1:p1"))
    }

    @Test fun sharedImagesUploadAndInsertPaths() {
        server.enqueue(MockResponse.Builder().body("""{"path":"/up/1-a.png"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"path":"/up/2-b.png"}""").build())
        val v = vm(ShareInput(null, listOf(img("a.png"), img("b.png"))))
        v.choose(target())
        val s = await(v)
        assertEquals("Image: /up/1-a.png\nImage: /up/2-b.png", drafts.get("m1", "w1:p1"))
        assertEquals(target(), s.opened)

        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", r.method)
        assertEquals("/v1/upload", r.url.encodedPath)
        assertEquals("w1:p1", r.url.queryParameter("pane_id"))
        assertNull("bridge refuses requests carrying Origin", r.headers["Origin"])
        assertEquals("127.0.0.1:${server.port}", r.headers["Host"])
        val body = r.body!!.utf8()
        assertTrue(body.contains("""name="file"; filename="a.png""""))
    }

    @Test fun uploadErrorKeepsDraft() {
        server.enqueue(MockResponse.Builder().body("""{"path":"/up/1-a.png"}""").build())
        server.enqueue(MockResponse.Builder().code(500).body("""{"error":"disk full"}""").build())
        val v = vm(ShareInput("note", listOf(img("a.png"), img("b.png"))))
        v.choose(target())
        val s = await(v)
        assertEquals("note\nImage: /up/1-a.png", drafts.get("m1", "w1:p1"))
        assertNull(s.opened)
        assertEquals(listOf("b.png"), s.failed.map { it.image.name })
        assertTrue(s.error!!.contains("disk full"))

        // retry uploads only the failed one; the text is not added twice
        server.enqueue(MockResponse.Builder().body("""{"path":"/up/3-b.png"}""").build())
        v.retry()
        val s2 = await(v)
        assertEquals("note\nImage: /up/1-a.png\nImage: /up/3-b.png", drafts.get("m1", "w1:p1"))
        assertEquals(target(), s2.opened)
        assertTrue(s2.failed.isEmpty())
    }

    @Test fun oversizedImageFailsBeforeUploading() {
        val v = vm(ShareInput(null, listOf(img("big.png", size = 26L * 1024 * 1024))))
        v.choose(target())
        val s = await(v)
        assertEquals(0, server.requestCount)
        assertNotNull(s.error)
        assertEquals("", drafts.get("m1", "w1:p1"))
    }

    @Test fun removedMachineFails() {
        val v = ShareViewModel(
            ShareInput(null, listOf(img("a.png"))),
            object : ShareBackend {
                override fun agentPanes() = flowOf(listOf(target()))
                override fun machine(id: String): Machine? = null
            },
            Uploader(OkHttpClient()), drafts, scope,
        )
        v.choose(target())
        assertNotNull(await(v).error)
    }
}

class ShareUriTest {
    private fun p(s: String?, a: String?) = shareUriProblem(s, a, "dev.whistle.app")

    @Test fun acceptsForeignContentUris() {
        assertNull(p("content", "com.android.providers.media.documents"))
        assertNull(p("content", "media"))
    }

    @Test fun refusesFileAndOtherSchemes() {
        assertNotNull(p("file", ""))
        assertNotNull(p("http", "example.com"))
        assertNotNull(p(null, null))
    }

    @Test fun refusesOwnAuthorities() {
        assertNotNull(p("content", "dev.whistle.app"))
        assertNotNull(p("content", "dev.whistle.app.fileprovider"))
        assertNotNull(p("content", "user@DEV.Whistle.App.x"))
        assertNotNull(p("content", null))
    }
}
