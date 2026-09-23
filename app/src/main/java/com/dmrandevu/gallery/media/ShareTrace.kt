package com.dmrandevu.gallery.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A running record of every video handed to Instagram: how long it was at each step, from what
 * the player saw to the file Instagram was given.
 *
 * Here for the videos that sometimes arrive in Instagram at 15 seconds. Nothing on the way
 * trims, and every one caught so far was 15 seconds at the source — but it happens now and then,
 * and logcat on the phone only reaches back a few hours. So this also goes to a file that
 * survives restarts:
 *
 *     adb exec-out run-as com.dmrandevu.gallery cat files/share-trace.log
 *
 * A step that comes out shorter than the one before it, or at about 15 seconds, is marked
 * SUSPECT, so the line to look for is easy to find.
 */
object ShareTrace {

    private const val TAG = "ShareTrace"
    private const val FILE = "share-trace.log"

    /** Past this the log rolls over to a single `.1` backup, so it stays bounded. */
    private const val MAX_BYTES = 512 * 1024L

    /** Instagram's own clip length, which is what the suspicious videos keep coming out at. */
    private const val CLIP_MS = 15_000L
    private const val CLIP_TOLERANCE_MS = 600L

    /** A drop smaller than this is container rounding between steps, not lost video. */
    private const val SHRINK_TOLERANCE_MS = 1_000L

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, message: String) {
        Log.i(TAG, message)
        synchronized(this) {
            runCatching {
                val file = File(context.filesDir, FILE)
                if (file.length() > MAX_BYTES) {
                    file.renameTo(File(context.filesDir, "$FILE.1"))
                }
                file.appendText("${stamp.format(Date())} $message\n")
            }.onFailure { Log.w(TAG, "Could not write the trace file", it) }
        }
    }

    /**
     * Logs [file]'s length and size under [step]. Checks it against [expectedMs] (the
     * length an earlier step reported) and returns its own length for the next step to check.
     */
    fun probe(context: Context, step: String, file: File, expectedMs: Long?): Long? {
        val durationMs = durationOf(file)
        val flags = suspicion(durationMs, expectedMs)
        log(
            context,
            "$step: ${format(durationMs)}, ${file.length()} bytes" +
                (expectedMs?.let { " (was ${format(it)})" } ?: "") +
                (if (flags.isEmpty()) "" else " SUSPECT: ${flags.joinToString()}")
        )
        return durationMs ?: expectedMs
    }

    fun suspicion(durationMs: Long?, expectedMs: Long?): List<String> = buildList {
        if (durationMs == null) {
            add("unreadable")
            return@buildList
        }
        if (kotlin.math.abs(durationMs - CLIP_MS) <= CLIP_TOLERANCE_MS) add("~15s")
        if (expectedMs != null && expectedMs - durationMs > SHRINK_TOLERANCE_MS) add("shorter")
    }

    fun format(durationMs: Long?): String =
        durationMs?.let { String.format(Locale.US, "%.2fs", it / 1000.0) } ?: "?"

    private fun durationOf(file: File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        }
    }.getOrNull()
}
