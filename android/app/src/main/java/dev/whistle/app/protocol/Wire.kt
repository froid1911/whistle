@file:OptIn(ExperimentalSerializationApi::class)

package dev.whistle.app.protocol

import java.net.URI
import java.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/*
 * Wire protocol v1 (docs/reference/wire-protocol.md). Field names are kept
 * snake_case on the wire via @SerialName. Status/kind/role values stay Strings
 * so a newer bridge cannot break decoding; use the helper enums/constants.
 */

val WireJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true // Go encodes nil slices as null
    explicitNulls = false
    encodeDefaults = true
}

/**
 * A bridge endpoint. [baseUrl] must be `http://<ip>:<port>`: the bridge rejects any other Host.
 * After pairing, [deviceId] is this phone's id on the bridge, [keyAlias] its signing key in
 * [DeviceKeys], [bridgeKey] the pinned bridge identity (SPKI, standard base64) and [deviceName]
 * the name the phone registered under. Unpaired machines connect unsigned (legacy bridges only).
 */
data class Machine(
    val id: String,
    val name: String,
    val baseUrl: String,
    val deviceId: String? = null,
    val keyAlias: String? = null,
    val bridgeKey: String? = null,
    val deviceName: String? = null,
) {
    val wsUrl: String get() = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/v1/ws"

    /** Has a device key: every request is signed and the welcome must carry the pinned bridge's signature. */
    val paired: Boolean get() = deviceId != null && keyAlias != null && bridgeKey != null

    /** The same machine without its pairing (e.g. after the key is gone). */
    fun unpaired(): Machine = copy(deviceId = null, keyAlias = null, bridgeKey = null, deviceName = null)

    companion object {
        val DEFAULT = Machine("myserver", "myserver", "http://100.64.0.10:8787")

        /** null if [url] is usable, else a human-readable reason. */
        fun baseUrlProblem(url: String): String? {
            val u = runCatching { URI(url.trim()) }.getOrNull() ?: return "not a URL"
            if (u.scheme != "http") return "scheme must be http (traffic stays inside Tailscale)"
            val host = u.host ?: return "missing host"
            if (!isIpLiteral(host)) return "host must be an IP address (the bridge checks Host == ip:port)"
            if (u.port == -1) return "port is required"
            if (!u.path.isNullOrEmpty() && u.path != "/") return "no path allowed"
            return null
        }

        private fun isIpLiteral(host: String): Boolean =
            Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(host)
                ?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true ||
                (host.startsWith("[") && host.endsWith("]"))
    }
}

// ---- State ---------------------------------------------------------------

@Serializable
data class WState(
    val workspaces: List<WWorkspace> = emptyList(),
    val tabs: List<WTab> = emptyList(),
    val panes: List<WPane> = emptyList(),
    val layouts: List<WLayout> = emptyList(),
)

@Serializable
data class WWorkspace(
    val id: String,
    val number: Int = 0,
    val label: String = "",
    @SerialName("agent_status") val agentStatus: String? = null,
    @SerialName("active_tab_id") val activeTabId: String? = null,
)

@Serializable
data class WTab(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    @SerialName("agent_status") val agentStatus: String? = null,
)

@Serializable
data class WPane(
    val id: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val label: String = "",
    val cwd: String = "",
    val kind: String = PaneKind.SHELL,
    val agent: String? = null,
    @SerialName("agent_status") val agentStatus: String? = null,
    @SerialName("session_key") val sessionKey: String? = null,
    @SerialName("has_transcript") val hasTranscript: Boolean = false,
)

@Serializable
data class WLayout(
    @SerialName("tab_id") val tabId: String,
    val panes: List<WLayoutPane> = emptyList(),
)

@Serializable
data class WLayoutPane(
    @SerialName("pane_id") val paneId: String,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
)

@Serializable
data class WMessage(
    val id: String,
    val seq: Long,
    @SerialName("session_key") val sessionKey: String,
    val ts: String = "",
    val role: String,
    val text: String = "",
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /** Any JSON value: the contract says object (`{"_truncated": …}` when cut), but never trust it. */
    val input: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean = false,
    val truncated: Boolean = false,
    /**
     * Raw serialized input of a message read from the cache ([input] is then null): the chat list
     * keeps the string and decodes it only when a tool call is expanded ([toolInput]).
     */
    @kotlinx.serialization.Transient val inputJson: String? = null,
) {
    /** [input], or [inputJson] parsed on first access (null if absent or not JSON). Not part of equals. */
    val toolInput: JsonElement? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        input ?: inputJson?.let { runCatching { WireJson.parseToJsonElement(it) }.getOrNull() }
    }
}

/** `chat.history` result. */
@Serializable
data class WHistoryPage(val messages: List<WMessage> = emptyList(), val more: Boolean = false)

@Serializable
data class WError(val code: String, val message: String = "")

object PaneKind {
    const val AGENT = "agent"
    const val SHELL = "shell"
}

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val THINKING = "thinking"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val SCREEN = "screen"
}

object ChangeKind {
    const val UPSERT = "upsert"
    const val REMOVE = "remove"
    const val MESSAGE = "message"
    const val STATUS = "status"
}

object Entity {
    const val WORKSPACE = "workspace"
    const val TAB = "tab"
    const val PANE = "pane"
    const val LAYOUT = "layout"
}

/** Agent status. A finished turn may end in DONE rather than IDLE: both are "settled". */
enum class AgentStatus(val wire: String) {
    IDLE("idle"), WORKING("working"), BLOCKED("blocked"), DONE("done"), UNKNOWN("unknown");

    val settled: Boolean get() = this == IDLE || this == DONE

    companion object {
        fun parse(s: String?): AgentStatus = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

// ---- Server → client -------------------------------------------------------

@Serializable
@JsonClassDiscriminator("t")
sealed interface ServerMsg

@Serializable
@SerialName("welcome")
data class Welcome(
    val protocol: Int,
    @SerialName("server_id") val serverId: String,
    @SerialName("head_seq") val headSeq: Long,
    val resync: Boolean,
    @SerialName("herdr_version") val herdrVersion: String = "",
    /** Bridge identity signature over server_id + this connection's request nonce (signed connections only). */
    val sig: String? = null,
) : ServerMsg

@Serializable
@SerialName("state")
data class StateMsg(val seq: Long, val state: WState = WState()) : ServerMsg

@Serializable
@SerialName("synced")
data class Synced(val seq: Long) : ServerMsg

/** Durable change-log entry; which optional fields are set depends on [kind] (see [ChangeKind]). */
@Serializable
@SerialName("change")
data class Change(
    val seq: Long,
    val kind: String,
    val entity: String? = null,
    val data: JsonObject? = null,
    val id: String? = null,
    @SerialName("session_key") val sessionKey: String? = null,
    val message: WMessage? = null,
    @SerialName("pane_id") val paneId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val at: String? = null,
) : ServerMsg {
    /** Decodes [data] of an upsert, e.g. `dataAs<WPane>()` when entity == "pane". */
    inline fun <reified T> dataAs(): T? = data?.let { WireJson.decodeFromJsonElement<T>(it) }
}

@Serializable
@SerialName("live")
data class Live(
    @SerialName("pane_id") val paneId: String,
    val text: String = "",
    val status: String? = null,
) : ServerMsg

/** Ephemeral: whether the bridge currently reaches herdr. Sent right after welcome and on every change. */
@Serializable
@SerialName("herdr")
data class HerdrStatus(val online: Boolean) : ServerMsg

@Serializable
@SerialName("live.clear")
data class LiveClear(@SerialName("pane_id") val paneId: String) : ServerMsg

@Serializable
@SerialName("res")
data class Res(val id: String, val result: JsonElement? = null, val error: WError? = null) : ServerMsg

@Serializable
@SerialName("term.frame")
data class TermFrame(
    val sid: String,
    val seq: Long,
    val width: Int,
    val height: Int,
    val full: Boolean = false,
    val bytes: String = "",
) : ServerMsg {
    fun decodedBytes(): ByteArray = Base64.getDecoder().decode(bytes)
}

@Serializable
@SerialName("term.closed")
data class TermClosed(val sid: String, val reason: String = "") : ServerMsg

/**
 * Client-side marker, never on the wire: [BridgeClient] puts it on `syncEvents` at the start of
 * every connection attempt so the applier drops its baseline before any frame of the new connection.
 * A bridge sending `{"t":"client.connecting"}` gets it ignored as an unknown type.
 */
@Serializable
@SerialName(CONNECTION_STARTED_T)
data object ConnectionStarted : ServerMsg

const val CONNECTION_STARTED_T = "client.connecting"

/** Result of decoding one server frame. */
sealed interface Decoded {
    data class Msg(val msg: ServerMsg) : Decoded

    /** A `t` this client does not know (newer bridge): safe to ignore. */
    data class Unknown(val t: String) : Decoded

    /** Not a JSON object, no `t`, or a known `t` that fails to decode: must not be skipped silently. */
    data class Malformed(val t: String?, val error: String) : Decoded
}

private val knownServerTypes: Set<String> =
    ServerMsg.serializer().descriptor.getElementDescriptor(1).elementNames.toSet() - CONNECTION_STARTED_T

fun decodeServerFrame(text: String): Decoded {
    val obj = try {
        WireJson.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return Decoded.Malformed(null, "not a JSON object")
    val t = (obj["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return Decoded.Malformed(null, "missing t")
    if (t !in knownServerTypes) return Decoded.Unknown(t)
    return try {
        Decoded.Msg(WireJson.decodeFromJsonElement(ServerMsg.serializer(), obj))
    } catch (e: SerializationException) {
        Decoded.Malformed(t, e.message ?: e.toString())
    } catch (e: IllegalArgumentException) {
        Decoded.Malformed(t, e.message ?: e.toString())
    }
}

/** Convenience: the message, or null for unknown or malformed frames. */
fun decodeServerMsg(text: String): ServerMsg? = (decodeServerFrame(text) as? Decoded.Msg)?.msg

// ---- Client → server -------------------------------------------------------

@Serializable
@JsonClassDiscriminator("t")
sealed interface ClientMsg

@Serializable
@SerialName("hello")
data class Hello(
    @SerialName("server_id") val serverId: String,
    val since: Long,
    val client: String = "android/1",
) : ClientMsg

@Serializable
@SerialName("watch")
data class Watch(@SerialName("pane_id") val paneId: String) : ClientMsg

@Serializable
@SerialName("unwatch")
data class Unwatch(@SerialName("pane_id") val paneId: String) : ClientMsg

@Serializable
@SerialName("cmd")
data class Cmd(val id: String, val method: String, val params: JsonObject) : ClientMsg

@Serializable
@SerialName("term.open")
data class TermOpen(
    val sid: String,
    @SerialName("pane_id") val paneId: String,
    val mode: String, // "observe" | "control"
    val cols: Int? = null,
    val rows: Int? = null,
) : ClientMsg

@Serializable
@SerialName("term.input")
data class TermInput(val sid: String, val text: String? = null, val bytes: String? = null) : ClientMsg

@Serializable
@SerialName("term.resize")
data class TermResize(val sid: String, val cols: Int, val rows: Int) : ClientMsg

@Serializable
@SerialName("term.close")
data class TermClose(val sid: String) : ClientMsg

fun encodeClientMsg(msg: ClientMsg): String = WireJson.encodeToString(ClientMsg.serializer(), msg)
