package dev.whistle.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    private inline fun <reified T : ServerMsg> decode(json: String): T {
        val msg = decodeServerMsg(json)
        assertTrue("expected ${T::class.simpleName}, got $msg", msg is T)
        return msg as T
    }

    @Test
    fun decodesEveryServerMessage() {
        val welcome = decode<Welcome>(
            """{"t":"welcome","protocol":1,"server_id":"srv","head_seq":42,"resync":true,"herdr_version":"0.9.5"}""",
        )
        assertEquals(Welcome(1, "srv", 42, true, "0.9.5"), welcome)

        val state = decode<StateMsg>(
            """{"t":"state","seq":42,"state":{
              "workspaces":[{"id":"w1","number":1,"label":"whistle","agent_status":"working","active_tab_id":"w1:t1"}],
              "tabs":[{"id":"w1:t1","workspace_id":"w1","number":1,"label":"main"}],
              "panes":[{"id":"w1:p1","tab_id":"w1:t1","workspace_id":"w1","label":"claude","cwd":"/home/you/x",
                        "kind":"agent","agent":"claude","agent_status":"done","session_key":"claude:abc","has_transcript":true},
                       {"id":"w1:p2","tab_id":"w1:t1","workspace_id":"w1","label":"zsh","cwd":"/","kind":"shell","has_transcript":false}],
              "layouts":[{"tab_id":"w1:t1","panes":[{"pane_id":"w1:p1","x":0,"y":0,"w":80,"h":24}]}]}}""",
        )
        assertEquals(42L, state.seq)
        assertEquals(WWorkspace("w1", 1, "whistle", "working", "w1:t1"), state.state.workspaces.single())
        assertEquals(WTab("w1:t1", "w1", 1, "main", null), state.state.tabs.single())
        assertEquals(
            WPane("w1:p1", "w1:t1", "w1", "claude", "/home/you/x", "agent", "claude", "done", "claude:abc", true),
            state.state.panes[0],
        )
        assertNull(state.state.panes[1].sessionKey)
        assertEquals(WLayout("w1:t1", listOf(WLayoutPane("w1:p1", 0, 0, 80, 24))), state.state.layouts.single())

        assertEquals(Synced(42), decode<Synced>("""{"t":"synced","seq":42}"""))

        val upsert = decode<Change>(
            """{"t":"change","seq":43,"kind":"upsert","entity":"tab","data":{"id":"w1:t2","workspace_id":"w1","number":2,"label":"b"}}""",
        )
        assertEquals(ChangeKind.UPSERT, upsert.kind)
        assertEquals(Entity.TAB, upsert.entity)
        assertEquals(WTab("w1:t2", "w1", 2, "b", null), upsert.dataAs<WTab>())

        val remove = decode<Change>("""{"t":"change","seq":44,"kind":"remove","entity":"pane","id":"w1:p2"}""")
        assertEquals(Change(seq = 44, kind = "remove", entity = "pane", id = "w1:p2"), remove)

        val message = decode<Change>(
            """{"t":"change","seq":45,"kind":"message","session_key":"claude:abc","message":{
              "id":"m1","seq":45,"session_key":"claude:abc","ts":"2026-11-08T10:00:00Z","role":"tool_call",
              "text":"","tool_name":"Bash","tool_call_id":"tc1","input":{"command":"ls"},"truncated":true}}""",
        )
        val m = message.message!!
        assertEquals("claude:abc", message.sessionKey)
        assertEquals("m1", m.id)
        assertEquals(45L, m.seq)
        assertEquals("tool_call", m.role)
        assertEquals("Bash", m.toolName)
        assertEquals("tc1", m.toolCallId)
        assertEquals("ls", m.input!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertTrue(m.truncated)
        assertFalse(m.isError)

        val status = decode<Change>(
            """{"t":"change","seq":46,"kind":"status","pane_id":"w1:p1","session_key":"claude:abc","from":"working","to":"done","at":"2026-11-08T10:00:01Z"}""",
        )
        assertEquals("w1:p1", status.paneId)
        assertEquals("working", status.from)
        assertEquals("done", status.to)
        assertEquals("2026-11-08T10:00:01Z", status.at)

        assertEquals(
            Live("w1:p1", "thinking…", "working"),
            decode<Live>("""{"t":"live","pane_id":"w1:p1","text":"thinking…","status":"working"}"""),
        )
        assertEquals(LiveClear("w1:p1"), decode<LiveClear>("""{"t":"live.clear","pane_id":"w1:p1"}"""))

        val ok = decode<Res>("""{"t":"res","id":"c1","result":{"text":"hi"}}""")
        assertEquals("c1", ok.id)
        assertEquals("hi", ok.result!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(ok.error)
        val err = decode<Res>("""{"t":"res","id":"c2","error":{"code":"agent_blocked","message":"blocked"}}""")
        assertEquals(WError("agent_blocked", "blocked"), err.error)

        val frame = decode<TermFrame>(
            """{"t":"term.frame","sid":"s1","seq":3,"width":80,"height":24,"full":true,"bytes":"G1sySg=="}""",
        )
        assertEquals("s1", frame.sid)
        assertEquals(3L, frame.seq)
        assertEquals(80, frame.width)
        assertTrue(frame.full)
        assertArrayEquals(byteArrayOf(0x1b, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte()), frame.decodedBytes())

        assertEquals(TermClosed("s1", "ended"), decode<TermClosed>("""{"t":"term.closed","sid":"s1","reason":"ended"}"""))
    }

    @Test
    fun decodesHerdrStatus() {
        assertEquals(Decoded.Msg(HerdrStatus(false)), decodeServerFrame("""{"t":"herdr","online":false}"""))
        assertEquals(Decoded.Msg(HerdrStatus(true)), decodeServerFrame("""{"t":"herdr","online":true}"""))
        assertTrue(decodeServerFrame("""{"t":"herdr"}""") is Decoded.Malformed)
    }

    @Test
    fun ignoresUnknownType() {
        assertNull(decodeServerMsg("""{"t":"future.thing","x":1}"""))
        assertNull(decodeServerMsg("""{"no_t":true}"""))
        assertNull(decodeServerMsg("not json"))
    }

    @Test
    fun distinguishesUnknownTypeFromMalformedKnownType() {
        assertEquals(Decoded.Unknown("future.thing"), decodeServerFrame("""{"t":"future.thing","x":1}"""))
        assertTrue(decodeServerFrame("""{"t":"synced","seq":3}""") == Decoded.Msg(Synced(3)))
        // known t, missing required fields / wrong shape: must not be silently skipped
        assertTrue(decodeServerFrame("""{"t":"change","kind":"status"}""") is Decoded.Malformed)
        assertTrue(decodeServerFrame("""{"t":"change","seq":5,"kind":"message","message":{"id":"m"}}""") is Decoded.Malformed)
        assertTrue(decodeServerFrame("""{"t":"state","seq":"x"}""") is Decoded.Malformed)
        // no t / not an object / not JSON: protocol violation, treated as malformed
        assertTrue(decodeServerFrame("""{"no_t":true}""") is Decoded.Malformed)
        assertTrue(decodeServerFrame("[1]") is Decoded.Malformed)
        assertTrue(decodeServerFrame("not json") is Decoded.Malformed)
    }

    @Test
    fun ignoresUnknownFieldsAndGoNulls() {
        val w = decodeServerMsg(
            """{"t":"welcome","protocol":1,"server_id":"s","head_seq":0,"resync":false,"herdr_version":"","extra":{"a":1}}""",
        )
        assertEquals(Welcome(1, "s", 0, false, ""), w)
        // Go encodes nil slices as null.
        val st = decodeServerMsg("""{"t":"state","seq":1,"state":{"workspaces":null,"tabs":[],"panes":[],"layouts":[{"tab_id":"t","panes":null}]}}""")
        st as StateMsg
        assertTrue(st.state.workspaces.isEmpty())
        assertTrue(st.state.layouts.single().panes.isEmpty())
    }

    @Test
    fun encodesClientMessages() {
        val hello = WireJson.parseToJsonElement(encodeClientMsg(Hello(serverId = "", since = 0))).jsonObject
        assertEquals("hello", hello["t"]!!.jsonPrimitive.content)
        assertEquals("", hello["server_id"]!!.jsonPrimitive.content)
        assertEquals(0L, hello["since"]!!.jsonPrimitive.long)
        assertEquals("android/1", hello["client"]!!.jsonPrimitive.content)

        val cmd = WireJson.parseToJsonElement(
            encodeClientMsg(Cmd("c7", "agent.prompt", buildJsonObject { put("pane_id", "w1:p1") })),
        ).jsonObject
        assertEquals(
            JsonObject(
                mapOf(
                    "t" to JsonPrimitive("cmd"), "id" to JsonPrimitive("c7"), "method" to JsonPrimitive("agent.prompt"),
                    "params" to buildJsonObject { put("pane_id", "w1:p1") },
                ),
            ),
            cmd,
        )

        val open = WireJson.parseToJsonElement(encodeClientMsg(TermOpen("s1", "w1:p1", "observe"))).jsonObject
        assertEquals("term.open", open["t"]!!.jsonPrimitive.content)
        assertEquals("w1:p1", open["pane_id"]!!.jsonPrimitive.content)
        assertFalse("absent cols must be omitted", "cols" in open)

        val resize = WireJson.parseToJsonElement(encodeClientMsg(TermResize("s1", 100, 40))).jsonObject
        assertEquals(100, resize["cols"]!!.jsonPrimitive.int)
        assertEquals("unwatch", WireJson.parseToJsonElement(encodeClientMsg(Unwatch("p"))).jsonObject["t"]!!.jsonPrimitive.content)
    }

    @Test
    fun agentStatusDoneAndIdleAreSettled() {
        assertTrue(AgentStatus.parse("idle").settled)
        assertTrue(AgentStatus.parse("done").settled)
        assertFalse(AgentStatus.parse("working").settled)
        assertFalse(AgentStatus.parse("blocked").settled)
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.parse(null))
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.parse("something-new"))
    }

    @Test
    fun machineWsUrl() {
        assertEquals("ws://100.64.0.10:8787/v1/ws", Machine("m", "box", "http://100.64.0.10:8787").wsUrl)
        assertEquals("ws://100.64.0.10:8787/v1/ws", Machine("m", "box", "http://100.64.0.10:8787/").wsUrl)
        assertNull(Machine.baseUrlProblem("http://100.64.0.10:8788"))
        assertTrue(Machine.baseUrlProblem("http://myserver:8787") != null)
        assertTrue(Machine.baseUrlProblem("http://100.64.0.10") != null)
    }
}
