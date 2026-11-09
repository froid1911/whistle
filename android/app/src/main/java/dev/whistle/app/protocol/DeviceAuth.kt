package dev.whistle.app.protocol

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The phone's per-machine signing keys (EC P-256, SHA256withECDSA, DER signatures). On the device
 * this is the Android Keystore ([dev.whistle.app.security.KeystoreDeviceKeys]); JVM tests use a
 * software implementation. Private keys never leave the implementation.
 */
interface DeviceKeys {
    /** Creates a new key pair under [alias] (replacing any) and returns its SPKI (X.509) public key. */
    fun generate(alias: String): ByteArray

    /** ECDSA P-256 / SHA-256 signature of [data], DER encoded. */
    fun sign(alias: String, data: ByteArray): ByteArray

    fun has(alias: String): Boolean

    fun delete(alias: String)
}

/**
 * Whistle request authentication v2 (docs/reference/wire-protocol.md, "Authentication"): every
 * signed request carries a single-use server challenge from `GET /v1/challenge?device=<id>`, so a
 * captured request cannot be replayed. Must match the bridge's `internal/devauth` byte for byte;
 * the cross-side fixtures in the test resources (`whistle_fixture_go_v2.json` /
 * `whistle_fixture_kotlin_v2.json`) pin that. The app no longer sends v1 (replayable within the
 * bridge's 60 s skew window): paired machines need bridge plugin >= 0.3.0.
 */
object WhistleAuth {
    const val SCHEME = "Whistle v2"
    private val b64u = Base64.getUrlEncoder().withoutPadding()
    private val b64uDec = Base64.getUrlDecoder()

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** sha256 hex of the empty body (GET, WebSocket upgrade). */
    val EMPTY_BODY_HASH: String = sha256Hex(ByteArray(0))

    /** The v2 string a device signs (the v1 fields under its own prefix, then challenge and device id). */
    fun canonical(
        method: String,
        pathAndQuery: String,
        host: String,
        ts: Long,
        nonce: String,
        bodySha256Hex: String,
        challenge: String,
        deviceId: String,
    ): String = "whistle-v2\n$method\n$pathAndQuery\n$host\n$ts\n$nonce\n$bodySha256Hex\n$challenge\n$deviceId"

    fun header(deviceId: String, ts: Long, nonce: String, challenge: String, bodySha256Hex: String, sig: String): String =
        "$SCHEME device=$deviceId, ts=$ts, nonce=$nonce, challenge=$challenge, body=$bodySha256Hex, sig=$sig"

    fun welcomeMessage(serverId: String, nonce: String): String = "whistle-welcome-v1\n$serverId\n$nonce"

    /** The request target as OkHttp sends it: encoded path plus `?query`. */
    fun pathAndQuery(url: HttpUrl): String = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    /** The Host header as OkHttp sends it (port omitted only when it is the scheme default). */
    fun host(url: HttpUrl): String {
        val h = if (url.host.contains(':')) "[${url.host}]" else url.host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) h else "$h:${url.port}"
    }

    fun b64url(b: ByteArray): String = b64u.encodeToString(b)

    /** base64url(sha256(SPKI DER)), as in the pairing QR's `f`. */
    fun fingerprint(spki: ByteArray): String = b64url(MessageDigest.getInstance("SHA-256").digest(spki))

    /** A challenge as the bridge issues it: 16 bytes, base64url without padding. */
    fun isChallenge(s: String): Boolean =
        s.length == 22 && runCatching { b64uDec.decode(s).size == 16 }.getOrDefault(false)

    /** Verifies a base64url DER ECDSA P-256/SHA-256 signature against an SPKI public key. */
    fun verify(spki: ByteArray, message: String, sigB64url: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message.toByteArray(Charsets.UTF_8))
            verify(b64uDec.decode(sigB64url))
        }
    }.getOrDefault(false)
}

/**
 * `GET /v1/challenge` failed. [code] is the bridge's error code when it sent one;
 * [CHALLENGE_UNSUPPORTED] means the bridge has no such endpoint (plugin < 0.3.0, 404).
 */
class ChallengeException(val code: String?, message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        const val CHALLENGE_UNSUPPORTED = "challenge_unsupported"
    }
}

/** Signs requests for paired machines (v2: server challenge, see [WhistleAuth]). */
class RequestSigner(
    private val keys: DeviceKeys,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) {
    /** [authorization] goes into the Authorization header; [nonce] also binds the welcome signature. */
    data class Signed(val authorization: String, val nonce: String)

    /** null when [machine] has no device key (legacy, unsigned). */
    fun sign(
        machine: Machine,
        method: String,
        url: HttpUrl,
        challenge: String,
        bodySha256Hex: String = WhistleAuth.EMPTY_BODY_HASH,
    ): Signed? {
        val deviceId = machine.deviceId ?: return null
        val alias = machine.keyAlias ?: return null
        val nonce = WhistleAuth.b64url(ByteArray(16).also(random::nextBytes))
        val ts = nowSeconds()
        val msg = WhistleAuth.canonical(method, WhistleAuth.pathAndQuery(url), WhistleAuth.host(url), ts, nonce, bodySha256Hex, challenge, deviceId)
        val sig = WhistleAuth.b64url(keys.sign(alias, msg.toByteArray(Charsets.UTF_8)))
        return Signed(WhistleAuth.header(deviceId, ts, nonce, challenge, bodySha256Hex, sig), nonce)
    }

    /**
     * Fetches a fresh single-use challenge for [machine]'s device id (`GET /v1/challenge`, no
     * Authorization, no Origin). Throws [ChallengeException]; a 404 is [ChallengeException.CHALLENGE_UNSUPPORTED].
     */
    suspend fun fetchChallenge(client: OkHttpClient, machine: Machine): String {
        val deviceId = machine.deviceId ?: throw ChallengeException(null, "${machine.name} is not paired")
        val url = (machine.baseUrl.trimEnd('/') + "/v1/challenge").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("device", deviceId)?.build()
            ?: throw ChallengeException(null, "Bad machine address: ${machine.baseUrl}")
        val (status, text) = try {
            client.newCall(Request.Builder().url(url).get().header("Cache-Control", "no-store").build()).await()
        } catch (e: IOException) {
            throw ChallengeException(null, "Can't reach ${machine.name}: ${e.message}", e)
        }
        if (status == 404) {
            throw ChallengeException(
                ChallengeException.CHALLENGE_UNSUPPORTED,
                "${machine.name}'s bridge is too old for secure login (needs the herdr plugin whistle.bridge 0.3.0 or newer)",
            )
        }
        val o = runCatching { WireJson.parseToJsonElement(text).jsonObject }.getOrNull()
        if (status != 200) {
            val code = runCatching { o?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()
            throw ChallengeException(code, "${machine.name} refused a login challenge (HTTP $status${code?.let { " $it" } ?: ""})")
        }
        val ch = runCatching { o?.get("challenge")?.jsonPrimitive?.content }.getOrNull()
        if (ch == null || !WhistleAuth.isChallenge(ch)) throw ChallengeException(null, "${machine.name} sent a bad login challenge")
        return ch
    }

    /** [fetchChallenge] then [sign]; null when [machine] is not paired. */
    suspend fun signFresh(
        client: OkHttpClient,
        machine: Machine,
        method: String,
        url: HttpUrl,
        bodySha256Hex: String = WhistleAuth.EMPTY_BODY_HASH,
    ): Signed? {
        if (!machine.paired) return null
        val ch = fetchChallenge(client, machine)
        return withContext(Dispatchers.IO) { sign(machine, method, url, ch, bodySha256Hex) }
    }
}

/** Runs the call cancellably on OkHttp's dispatcher; returns (status, body text ≤ 64 KiB). */
internal suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val r = runCatching { response.use { it.code to it.peekBody(64 * 1024).string() } }
            r.fold({ cont.resume(it) }, { cont.resumeWithException(if (it is IOException) it else IOException(it)) })
        }
    })
}

/** Why a paired (or unpaired) machine refuses this phone; the client stops reconnecting. */
enum class AuthProblem {
    /** 401 device_revoked or WS close 4001: unpaired on the machine. */
    Revoked,

    /** 401 device_unknown: the bridge does not know this phone (e.g. its database was reset). */
    Unknown,

    /** The welcome signature does not match the pinned bridge key. */
    BridgeMismatch,

    /** 401 auth_required: the bridge only accepts paired devices and this machine has no key. */
    NotPaired,

    /** The bridge has no `GET /v1/challenge` (plugin < 0.3.0): it cannot do secure (v2) login. */
    BridgeTooOld,
    ;

    fun message(machine: String): String = when (this) {
        Revoked -> "This phone was removed from $machine. Pair again."
        Unknown -> "$machine doesn't know this phone anymore. Pair again."
        BridgeMismatch -> "This doesn't look like your $machine bridge. Whistle refused to connect."
        NotPaired -> "$machine only accepts paired phones. Pair this machine."
        BridgeTooOld -> "$machine's bridge is too old for secure login. Update the herdr plugin whistle.bridge to 0.3.0 or newer."
    }

    companion object {
        /** Maps a 401 error code from the bridge; null = transient (retry). */
        fun fromCode(code: String?): AuthProblem? = when (code) {
            "device_revoked" -> Revoked
            "device_unknown" -> Unknown
            "auth_required" -> NotPaired
            else -> null
        }
    }
}
