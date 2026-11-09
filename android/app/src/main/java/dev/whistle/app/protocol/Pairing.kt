package dev.whistle.app.protocol

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A pairing invitation from `herdr plugin action invoke whistle.bridge.pair`:
 * `whistle://pair?v=1&u=<ip:port>&t=<token>&f=<bridge key fingerprint>&n=<machine name>`.
 * [fingerprint] is null only for manual entry (address + code), which cannot pin the bridge.
 */
data class PairInvite(val address: String, val token: String, val fingerprint: String?, val name: String?) {
    val baseUrl: String get() = "http://$address"
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address.substringBeforeLast(':')
}

/** Input that is not a usable invitation; [message] is fit to show. */
class PairInputException(message: String) : Exception(message)

object PairInvites {
    const val TOKEN_LENGTH = 26

    /** Accepts a scanned/pasted `whistle://pair?…` link. */
    fun parse(text: String): PairInvite {
        val t = text.trim()
        if (!t.startsWith("whistle://pair", ignoreCase = true)) {
            throw PairInputException("Not a Whistle pairing code")
        }
        val uri = runCatching { URI(t) }.getOrNull() ?: throw PairInputException("Damaged pairing link")
        val q = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            k to URLDecoder.decode(v, "UTF-8")
        }
        val v = q["v"] ?: throw PairInputException("Pairing link has no version")
        if (v != "1") throw PairInputException("This pairing code needs a newer Whistle (version $v)")
        val address = q["u"]?.let(::checkAddress) ?: throw PairInputException("Pairing link has no address")
        val token = q["t"]?.let(::normalizeCode)?.takeIf { it.length == TOKEN_LENGTH }
            ?: throw PairInputException("Pairing link has no valid code")
        val fp = q["f"]?.takeIf { isFingerprint(it) } ?: throw PairInputException("Pairing link has no bridge fingerprint")
        return PairInvite(address, token, fp, q["n"]?.trim()?.take(64))
    }

    /** Manual entry: an address (`100.x.y.z:8787`, `http://` optional) and the grouped code. */
    fun manual(address: String, code: String): PairInvite {
        val a = checkAddress(address.trim().removePrefix("http://").trimEnd('/'))
        val token = normalizeCode(code)
        if (token.length != TOKEN_LENGTH) throw PairInputException("The code has $TOKEN_LENGTH letters and digits")
        return PairInvite(a, token, null, null)
    }

    /** Either a pasted link or address + code. */
    fun fromInput(linkOrAddress: String, code: String): PairInvite =
        if (linkOrAddress.trim().startsWith("whistle:", ignoreCase = true)) parse(linkOrAddress) else manual(linkOrAddress, code)

    fun normalizeCode(s: String): String = s.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }

    private fun isFingerprint(s: String) = s.length == 43 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun checkAddress(a: String): String {
        Machine.baseUrlProblem("http://$a")?.let { throw PairInputException("Address $a: $it") }
        return a
    }
}

/** What the bridge answered to `POST /v1/pair`. */
data class PairResult(val deviceId: String, val bridgeKey: String, val serverId: String, val keyAlias: String, val deviceName: String)

/** Pairing failed; [message] is fit to show. */
class PairException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Runs the pairing request with a fresh device key. */
class Pairer(private val client: OkHttpClient, private val keys: DeviceKeys) {
    @Serializable
    private data class Req(val token: String, val device_name: String, val public_key: String)

    /**
     * Generates a key under a new alias for [machineId], registers it with the bridge and checks the
     * bridge's key against the invite's fingerprint. On any failure the new key is deleted again (an
     * existing pairing for the machine stays untouched until the caller swaps it in).
     */
    suspend fun pair(invite: PairInvite, machineId: String, deviceName: String): PairResult = withContext(Dispatchers.IO) {
        val alias = "whistle-device-$machineId-${System.currentTimeMillis()}"
        val spki = try {
            keys.generate(alias)
        } catch (e: Exception) {
            throw PairException("Couldn't create a key on this phone: ${e.message}", e)
        }
        var ok = false
        try {
            val url = "${invite.baseUrl}/v1/pair".toHttpUrlOrNull() ?: throw PairException("Bad address ${invite.address}")
            val name = deviceName.trim().ifEmpty { "phone" }.take(64)
            val body = WireJson.encodeToString(Req.serializer(), Req(invite.token, name, Base64.getEncoder().encodeToString(spki)))
            val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
            val text: String
            val code: Int
            try {
                client.newCall(req).execute().use { r ->
                    code = r.code
                    text = r.body.string()
                }
            } catch (e: IOException) {
                throw PairException("Can't reach ${invite.displayName} at ${invite.address}. Is Tailscale on?", e)
            }
            val o = runCatching { WireJson.parseToJsonElement(text).jsonObject }.getOrNull()
            when {
                code == 403 -> throw PairException("That code is wrong, expired or already used. Run pair on the machine again.")
                code == 429 -> throw PairException("Too many wrong codes. Wait a minute and try again.")
                code != 200 || o == null -> throw PairException("Pairing failed (HTTP $code)")
            }
            val deviceId = o!!["device_id"]?.jsonPrimitive?.content
            val bridgeKey = o["bridge_public_key"]?.jsonPrimitive?.content
            val serverId = o["server_id"]?.jsonPrimitive?.content.orEmpty()
            if (deviceId.isNullOrEmpty() || bridgeKey.isNullOrEmpty()) throw PairException("Pairing failed: unexpected reply")
            val keyDer = runCatching { Base64.getDecoder().decode(bridgeKey) }.getOrNull()
                ?: throw PairException("Pairing failed: bad bridge key")
            if (invite.fingerprint != null && WhistleAuth.fingerprint(keyDer) != invite.fingerprint) {
                throw PairException("This doesn't look like your ${invite.displayName} bridge (its key doesn't match the QR code). Not paired.")
            }
            ok = true
            PairResult(deviceId, bridgeKey, serverId, alias, name)
        } finally {
            if (!ok) runCatching { keys.delete(alias) }
        }
    }
}
