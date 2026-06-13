package com.eugen.ankiaudio

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * The on-disk store of synthesized Edge-TTS MP3s, shared by [EdgeTts] (which
 * reads and writes it), [TtsPrefetcher] (which warms it ahead of playback) and
 * the cleanup sweep that keeps it from growing without bound.
 *
 * Files are named by a hash of (voice, text), so the same sentence in the same
 * voice costs the network at most once. Every read [touch]es the file so its
 * "modified" time doubles as a *last used* time — that's what lets [trim] evict
 * the least-recently-used entries first instead of blindly deleting by age.
 */
object TtsCache {

    private const val TAG = "TtsCache"
    private const val DIR = "tts"

    /** Hard ceiling; once the cache passes it the sweep trims back to [TARGET_BYTES]. */
    private const val MAX_BYTES = 80L * 1024 * 1024    // ~80 MB
    private const val TARGET_BYTES = 50L * 1024 * 1024 // trim down to ~50 MB

    /** Entries untouched this long are dropped regardless of total size. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

    /** A half-written temp file (.tmp) older than this is a leak — clean it up. */
    private const val STALE_TMP_MS = 10L * 60 * 1000L // 10 minutes

    /** The cache directory, created on demand. */
    fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    /** The cache file for [text] in [voice] (it may or may not exist yet). */
    fun fileFor(context: Context, voice: String, text: String): File =
        File(dir(context), key(voice, text))

    /** Marks [file] as just-used so LRU eviction keeps it around longer. */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    private fun key(voice: String, text: String): String =
        sha256Hex("$voice|$text").take(40) + ".mp3"

    /**
     * Drops stale entries and, if the cache is still over budget, the
     * least-recently-used ones until it fits in [TARGET_BYTES]. Cheap and
     * idempotent — safe to call after every prefetch batch.
     */
    @Synchronized
    fun trim(context: Context) {
        val all = dir(context).listFiles()?.filter { it.isFile } ?: return
        if (all.isEmpty()) return

        val now = System.currentTimeMillis()
        var removed = 0
        var freed = 0L

        // Drop completed entries that have aged out, plus any leaked temp files.
        val live = ArrayList<File>(all.size)
        for (f in all) {
            val tooOld = now - f.lastModified() > MAX_AGE_MS
            val staleTmp = f.name.endsWith(".tmp") && now - f.lastModified() > STALE_TMP_MS
            if (tooOld || staleTmp) {
                freed += f.length(); if (f.delete()) removed++
            } else if (!f.name.endsWith(".tmp")) {
                live += f
            }
        }

        // Still too big? Evict oldest-touched first until under the target.
        var total = live.sumOf { it.length() }
        if (total > MAX_BYTES) {
            for (f in live.sortedBy { it.lastModified() }) {
                if (total <= TARGET_BYTES) break
                val len = f.length()
                if (f.delete()) { total -= len; freed += len; removed++ }
            }
        }

        if (removed > 0) Log.i(TAG, "trimmed $removed file(s), freed ${freed / 1024} KB")
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
