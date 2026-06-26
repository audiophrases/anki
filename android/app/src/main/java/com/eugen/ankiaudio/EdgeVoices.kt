package com.eugen.ankiaudio

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * The catalog of Microsoft Edge "Read Aloud" neural voices, and the user's
 * saved choice of which one to study with.
 *
 * The full list (~320 voices, every language) is fetched once from the same
 * unofficial endpoint [EdgeTts] synthesizes through, then cached on disk so
 * later launches are instant and work offline. If the fetch fails on a cold
 * first run, a short bundled [FALLBACK] list of popular voices is offered so
 * the picker is never empty.
 *
 * The chosen voice's `ShortName` (e.g. "en-US-AndrewMultilingualNeural") is
 * persisted in the shared prefs and read back by [CardSpeaker] for every
 * utterance, so a change takes effect on the next card without a restart.
 */
object EdgeVoices {

    /** One selectable voice. [shortName] is what the synthesizer needs. */
    data class Voice(
        val shortName: String,
        val locale: String,
        val gender: String,
        /** Human label, e.g. "English (United States) — Andrew · multilingual, Male". */
        val label: String,
    )

    private const val TAG = "EdgeVoices"
    private const val PREFS = "ankiaudio"
    private const val PREF_VOICE = "voice"
    private const val CACHE_FILE = "edge-voices.json"
    private const val CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // refresh monthly

    // Plain GET, no Sec-MS-GEC token needed (unlike synthesis). The trusted
    // client token is a long-stable literal shared with edge-tts.
    private const val VOICES_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud" +
            "/voices/list?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private val client = OkHttpClient()

    // ---- saved preference ----

    /** The user's chosen voice, or the project default if none saved yet. */
    fun savedVoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_VOICE, EdgeTts.DEFAULT_VOICE) ?: EdgeTts.DEFAULT_VOICE

    fun saveVoice(context: Context, shortName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_VOICE, shortName).apply()
    }

    // ---- catalog ----

    /**
     * The full voice catalog, English voices first then alphabetical by
     * language. Cache-first; fetches over the network only when the cache is
     * missing or stale; never throws — falls back to [FALLBACK].
     */
    suspend fun list(context: Context): List<Voice> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, CACHE_FILE)
        val fresh = cache.length() > 0 &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

        val json = when {
            fresh -> cache.readText()
            else -> fetch(cache) ?: cache.takeIf { it.length() > 0 }?.readText()
        }

        val parsed = json?.let { runCatching { parse(it) }.getOrNull() }
        (parsed?.takeIf { it.isNotEmpty() } ?: FALLBACK).sortedWith(ORDER)
    }

    /** GETs the catalog and writes it to [cache]; returns the body, or null on failure. */
    private fun fetch(cache: File): String? = try {
        val request = Request.Builder()
            .url(VOICES_URL)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
            )
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                cache.writeText(body)
                Log.i(TAG, "fetched voice catalog (${body.length} bytes)")
                body
            } else {
                Log.w(TAG, "voices fetch failed: HTTP ${resp.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "voices fetch failed", e)
        null
    }

    private fun parse(json: String): List<Voice> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val shortName = o.optString("ShortName").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val locale = o.optString("Locale").ifBlank { shortName.substringBeforeLast('-') }
            val gender = o.optString("Gender").ifBlank { "" }
            Voice(shortName, locale, gender, labelFor(shortName, locale, gender))
        }
    }

    /** "en-US-AndrewMultilingualNeural" → "English (United States) — Andrew · multilingual, Male". */
    private fun labelFor(shortName: String, locale: String, gender: String): String {
        val language = runCatching {
            Locale.forLanguageTag(locale).getDisplayName(Locale.ENGLISH)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: locale

        var name = shortName.removePrefix("$locale-").removeSuffix("Neural")
        val multilingual = name.contains("Multilingual")
        name = name.replace("Multilingual", "").trim().ifBlank { shortName }

        return buildString {
            append(language)
            append(" — ")
            append(name)
            if (multilingual) append(" · multilingual")
            if (gender.isNotBlank()) append(", $gender")
        }
    }

    // English first, US English at the very top, then alphabetical by label.
    private val ORDER = compareBy<Voice>(
        { if (it.locale.startsWith("en")) 0 else 1 },
        { if (it.locale == "en-US") 0 else 1 },
        { it.label },
    )

    // Popular voices offered if the catalog can't be fetched on a cold first
    // run (offline). Once the real catalog loads, it replaces these.
    private val FALLBACK: List<Voice> = listOf(
        Triple("en-US-AndrewMultilingualNeural", "en-US", "Male"),
        Triple("en-US-AvaMultilingualNeural", "en-US", "Female"),
        Triple("en-US-BrianMultilingualNeural", "en-US", "Male"),
        Triple("en-US-EmmaMultilingualNeural", "en-US", "Female"),
        Triple("en-US-GuyNeural", "en-US", "Male"),
        Triple("en-US-JennyNeural", "en-US", "Female"),
        Triple("en-GB-RyanNeural", "en-GB", "Male"),
        Triple("en-GB-SoniaNeural", "en-GB", "Female"),
        Triple("en-AU-NatashaNeural", "en-AU", "Female"),
        Triple("en-IN-PrabhatNeural", "en-IN", "Male"),
        Triple("es-ES-AlvaroNeural", "es-ES", "Male"),
        Triple("fr-FR-DeniseNeural", "fr-FR", "Female"),
        Triple("de-DE-KatjaNeural", "de-DE", "Female"),
        Triple("it-IT-ElsaNeural", "it-IT", "Female"),
        Triple("pt-BR-FranciscaNeural", "pt-BR", "Female"),
        Triple("ru-RU-SvetlanaNeural", "ru-RU", "Female"),
        Triple("zh-CN-XiaoxiaoNeural", "zh-CN", "Female"),
        Triple("ja-JP-NanamiNeural", "ja-JP", "Female"),
        Triple("ko-KR-SunHiNeural", "ko-KR", "Female"),
        Triple("hi-IN-SwaraNeural", "hi-IN", "Female"),
        Triple("ar-SA-ZariyahNeural", "ar-SA", "Female"),
    ).map { (s, l, g) -> Voice(s, l, g, labelFor(s, l, g)) }
}
