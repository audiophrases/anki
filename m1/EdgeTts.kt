package com.eugen.ankiaudio

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Client for Microsoft Edge's "Read Aloud" neural TTS endpoint (the same
 * service the edge-tts ecosystem uses). Unofficial but long-stable; the
 * caller must be ready for failures and fall back to the platform TTS.
 *
 * Synthesized MP3s are cached on disk keyed by (voice, text) so each card
 * costs network exactly once.
 */
object EdgeTts {

    const val DEFAULT_VOICE = "en-US-AndrewMultilingualNeural"

    private const val TAG = "EdgeTts"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    // Must track current edge-tts values or the server returns 403.
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR = "143"
    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a cached or freshly synthesized MP3 for [text]. Throws on failure.
     *
     * When [ssml] is non-null it is sent to Edge verbatim as the `<voice>` body
     * (the caller has already XML-escaped the literal text and added markup such
     * as `<prosody>`/`<break>`), and it — not [text] — keys the cache so a
     * prosody variant is stored separately. [text] stays the plain fallback form.
     */
    suspend fun synthesize(
        context: Context,
        text: String,
        voice: String = DEFAULT_VOICE,
        ssml: String? = null,
    ): File {
        val key = ssml ?: text
        val cached = TtsCache.fileFor(context, voice, key)
        if (cached.length() > 0) {
            Log.i(TAG, "cache hit for \"${key.take(40)}\"")
            TtsCache.touch(cached) // refresh last-used time for LRU eviction
            return cached
        }
        Log.i(TAG, "cache miss, requesting \"${key.take(40)}\"")
        // OkHttp read timeouts don't apply to websocket streams, and the server
        // can close without sending audio — bound the whole exchange ourselves.
        val bytes = kotlinx.coroutines.withTimeout(15_000) {
            requestAudio(ssml ?: escapeXml(text), voice)
        }
        // Write to a temp file then atomically swap it in: a cancelled write, or
        // two workers racing the same key, can never leave a half-written MP3
        // that a later run would mistake for a valid cache hit.
        val tmp = File(cached.parentFile, "${cached.name}.${UUID.randomUUID()}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(cached)) {
            tmp.copyTo(cached, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "synthesized ${bytes.size} bytes for \"${key.take(40)}\"")
        return cached
    }

    /** [body] is the already-escaped SSML inner content of the `<voice>` element. */
    private suspend fun requestAudio(body: String, voice: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/" +
                "readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&Sec-MS-GEC=${secMsGec()}" +
                "&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION" +
                "&ConnectionId=${uuid()}"
            val request = Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 " +
                        "Edg/$CHROMIUM_MAJOR.0.0.0"
                )
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=${muid()};")
                .build()

            val audio = ByteArrayOutputStream()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        "X-Timestamp:${timestamp()}\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":""" +
                            """{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                            """"outputFormat":"$OUTPUT_FORMAT"}}}}"""
                    )
                    webSocket.send(
                        "X-RequestId:${uuid()}\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${timestamp()}\r\n" +
                            "Path:ssml\r\n\r\n" +
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                            "xml:lang='en-US'><voice name='$voice'>$body</voice></speak>"
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        if (!cont.isActive) return
                        val bytes = audio.toByteArray()
                        if (bytes.isNotEmpty()) {
                            cont.resume(bytes)
                        } else {
                            cont.resumeWithException(IllegalStateException("no audio received"))
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size < 2) return
                    val headerLen =
                        ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size < 2 + headerLen) return
                    val header = bytes.substring(2, 2 + headerLen).utf8()
                    if (header.contains("Path:audio")) {
                        audio.write(bytes.substring(2 + headerLen).toByteArray())
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Server closed without turn.end — fail instead of hanging.
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("server closed early: $code $reason")
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }

    /**
     * Clock-derived token the endpoint requires: SHA-256 of Windows-epoch
     * ticks (rounded down to 5 min) + the trusted client token.
     */
    private fun secMsGec(): String {
        var seconds = System.currentTimeMillis() / 1000 + 11_644_473_600L
        seconds -= seconds % 300
        return sha256Hex("${seconds * 10_000_000}$TRUSTED_CLIENT_TOKEN").uppercase()
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun uuid(): String = UUID.randomUUID().toString().replace("-", "")

    /** Random 16-byte uppercase hex MUID for the cookie the endpoint expects. */
    private fun muid(): String = (uuid().take(32)).uppercase()

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
