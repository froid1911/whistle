package dev.whistle.app.protocol

import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source

const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

/** An upload that did not succeed; [message] is fit to show. */
class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `POST /v1/upload` of the bridge: one file per request, streamed (never held in memory).
 *
 * [sessionReady] gates uploads: it must return true only when the machine's current connection
 * got its welcome (verified against the pinned bridge key for a paired machine), so a file never
 * goes to an impostor at the machine's address. null = no gate (tests).
 */
class Uploader(
    private val client: OkHttpClient,
    private val signer: RequestSigner? = null,
    private val sessionReady: (suspend (Machine) -> Boolean)? = null,
) {
    /**
     * Uploads the stream from [open] to [machine] for [paneId]; returns the bridge-side path.
     * [size] is the known length or -1. Requests carry no Origin header and use the machine's
     * ip:port as Host (OkHttp's defaults). A paired machine's upload is signed (v2) over the
     * body's sha256 with a fresh server challenge; a stale challenge is retried once.
     */
    suspend fun upload(
        machine: Machine,
        paneId: String,
        name: String,
        mime: String?,
        size: Long,
        open: () -> InputStream,
    ): String = withContext(Dispatchers.IO) {
        if (size > MAX_UPLOAD_BYTES) throw UploadException("$name is larger than 25 MB")
        val url = try {
            (machine.baseUrl.trimEnd('/') + "/v1/upload").toHttpUrl().newBuilder().addQueryParameter("pane_id", paneId).build()
        } catch (e: IllegalArgumentException) {
            throw UploadException("Bad machine address: ${machine.baseUrl}", e)
        }
        val gate = sessionReady
        if (gate != null && !gate(machine)) throw UploadException("Not connected to ${machine.name}")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, StreamBody(mime?.toMediaTypeOrNull(), size, open))
            .build()
        val signs = machine.paired && signer != null
        // The signature covers sha256(body): write the (re-openable) body once into a hash, then send it.
        val hash = if (signs) {
            try {
                bodySha256(body)
            } catch (e: IOException) {
                throw UploadException(e.message ?: "Upload failed", e)
            }
        } else null
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder().url(url).post(body)
            if (signs) {
                val signed = try {
                    signer!!.signFresh(client, machine, "POST", url, hash!!)
                } catch (e: ChallengeException) {
                    if (e.code == ChallengeException.CHALLENGE_UNSUPPORTED) throw UploadException(e.message ?: "Upload failed", e)
                    if (attempt == 1 && e.code != null) continue
                    throw UploadException(e.message ?: "Upload failed", e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw UploadException("This phone has no key for ${machine.name}. Pair again.", e)
                } ?: throw UploadException("This phone has no key for ${machine.name}. Pair again.")
                req.header("Authorization", signed.authorization)
            }
            val call = client.newCall(req.build())
            val r = try {
                call.execute().use { resp ->
                    val text = resp.body.string()
                    if (resp.code == 401) {
                        val code = errorCode(text)
                        if (signs && code == "auth_stale" && attempt == 1) return@use null // fresh challenge, once
                        AuthProblem.fromCode(code)?.let { throw UploadException(it.message(machine.name)) }
                    }
                    if (!resp.isSuccessful) throw UploadException("Upload failed (HTTP ${resp.code})${errorDetail(text)}")
                    runCatching { Json.parseToJsonElement(text).jsonObject["path"]?.jsonPrimitive?.content }.getOrNull()
                        ?: throw UploadException("Upload failed: unexpected reply")
                }
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (e: UploadException) {
                throw e
            } catch (e: IOException) {
                throw UploadException(e.message ?: "Upload failed", e)
            }
            if (r != null) return@withContext r
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun errorCode(text: String): String? = runCatching {
        Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
    }.getOrNull()

    private fun bodySha256(body: RequestBody): String {
        val sink = HashingSink.sha256(blackholeSink())
        val buffered = sink.buffer()
        body.writeTo(buffered)
        buffered.flush()
        return sink.hash.hex()
    }

    private fun errorDetail(text: String): String {
        val msg = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            val e = o["error"]
            ((e as? kotlinx.serialization.json.JsonObject)?.get("message") ?: e ?: o["message"])?.jsonPrimitive?.content
        }.getOrNull()
        return if (msg.isNullOrBlank()) "" else ": $msg"
    }

    private class StreamBody(val type: MediaType?, val size: Long, val open: () -> InputStream) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = size
        override fun isOneShot() = false
        override fun writeTo(sink: BufferedSink) {
            open().use { stream ->
                val src = stream.source()
                var total = 0L
                val buf = okio.Buffer()
                while (true) {
                    val n = src.read(buf, 64 * 1024)
                    if (n < 0) break
                    total += n
                    if (total > MAX_UPLOAD_BYTES) throw IOException("File is larger than 25 MB")
                    sink.write(buf, n)
                }
            }
        }
    }
}
