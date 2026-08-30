package com.dmrandevu.gallery.media.censor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.dmrandevu.gallery.media.ExportOptions
import com.dmrandevu.gallery.media.VideoExporter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The whole censor path over a real clip, on the phone that has to run it.
 *
 * The clip is one of the operator's own, pushed to /data/local/tmp/censor_test.mp4 rather than
 * committed — it is a customer's video. It contains "Amına koydum" at about 30.7 s, which is the
 * phrase the desktop spike measured everything else against. Skips when it is not there.
 */
@UnstableApi
class CensorAudioExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = CensorModels(context, OkHttpClient())

    @Test
    fun beepsTheSwearingAndLeavesTheRestAlone() = runBlocking {
        val input = File("/data/local/tmp/censor_test.mp4")
        assumeTrue("no test clip", input.exists())
        assumeTrue("models not installed", models.allInstalled)

        val output = File(context.cacheDir, "censored.mp4")
        output.delete()

        val startedAt = System.currentTimeMillis()
        val result = VideoExporter(context, AudioCensor(context, models)).export(
            input, output, ExportOptions(censorAudio = true)
        ) { Log.i(TAG, "progress $it") }
        Log.i(TAG, "export took ${System.currentTimeMillis() - startedAt} ms")

        val exported = result as? VideoExporter.Result.Exported
        assertTrue("nothing was censored: $result", exported != null)
        val windows = exported!!.censored.orEmpty()
        Log.i(TAG, "windows: " + windows.joinToString { "${it.startUs / 1000}-${it.endUs / 1000}ms" })
        assertTrue("no censor windows", windows.isNotEmpty())

        // The phrase the spike located, verified by cutting the audio at those times and hearing
        // exactly it. Containment, not overlap: the first version of this test asked only that
        // some window touch the phrase, and passed while the beep sat 700 ms early — which is
        // what the operator heard and the test did not.
        val knownFrom = 30_680_000L
        val knownTo = 31_780_000L
        val covering = windows.filter { it.startUs <= knownFrom && it.endUs >= knownTo }
        assertTrue(
            "no window covers ${knownFrom / 1000}-${knownTo / 1000}ms; got " +
                windows.joinToString { "${it.startUs / 1000}-${it.endUs / 1000}" },
            covering.isNotEmpty()
        )
        // And it must not be covering it by being enormous. The phrase is 1.1 s and this allows
        // 2.0 s, which is not a comfortable margin — it is the current honest limit.
        //
        // On this clip the recognizer's timings cannot be calibrated (its words are reported
        // back to back, covering 80% of the audio, so there are no gaps to align against) and
        // the residual uncertainty is close to a second. A window narrower than this would stop
        // before the end of the swearing on the very clip it was measured against. Tightening it
        // needs a better timing source, not a smaller number here.
        assertTrue(
            "the covering window is too wide: " +
                covering.joinToString { "${(it.endUs - it.startUs) / 1000}ms" },
            covering.any { it.endUs - it.startUs < 2_000_000 }
        )

        assertTrue("no output", output.exists() && output.length() > 0)
        assertVideoIntact(input, output)
        assertBeepInside(output, windows)
    }

    @Test
    fun aClipWithNoSwearingIsLeftCompletelyAlone() = runBlocking {
        val input = File("/data/local/tmp/censor_clean.mp4")
        assumeTrue("no clean clip", input.exists())
        assumeTrue("models not installed", models.allInstalled)

        val output = File(context.cacheDir, "clean.mp4")
        output.delete()

        val result = VideoExporter(context, AudioCensor(context, models)).export(
            input, output, ExportOptions(censorAudio = true)
        ) {}

        // Nothing to censor and no picture filter, so the original is handed over untouched
        // rather than re-encoded to change nothing.
        assertEquals(VideoExporter.Result.NothingToDo, result)
    }

    /** The picture and the track layout have to survive the audio being rebuilt. */
    private fun assertVideoIntact(input: File, output: File) {
        val before = probe(input)
        val after = probe(output)
        assertEquals("width", before.width, after.width)
        assertEquals("height", before.height, after.height)
        assertEquals("rotation", before.rotation, after.rotation)
        assertTrue("audio track missing", after.hasAudio)
        assertTrue(
            "duration moved: ${before.durationUs} to ${after.durationUs}",
            abs(before.durationUs - after.durationUs) < 500_000
        )
    }

    /**
     * Listens for the beep where it should be, and for its absence where it should not.
     *
     * A Goertzel filter at 1 kHz rather than a full transform: one bin is all this needs, and the
     * question is only whether that bin dominates.
     */
    private fun assertBeepInside(output: File, windows: List<CensorWindow>) = runBlocking {
        val audio = AudioTrackDecoder().decode(output) {} ?: error("exported file has no audio")
        val window = windows.first()

        val inside = goertzelShare(audio, window.startUs, window.endUs)
        // Well clear of the window, and of the crossfades at its edges.
        val outsideFrom = (window.endUs + 2_000_000).coerceAtMost(audio.durationUs - 1_000_000)
        val outside = goertzelShare(audio, outsideFrom, outsideFrom + 800_000)

        Log.i(TAG, "1 kHz share inside=$inside outside=$outside")
        assertTrue("no beep inside the window (share $inside)", inside > 0.2)
        assertTrue(
            "the beep leaked outside the window (inside $inside, outside $outside)",
            inside > outside * 10
        )
    }

    /** How much of the energy in a stretch sits at 1 kHz. */
    private fun goertzelShare(
        audio: AudioTrackDecoder.DecodedAudio,
        fromUs: Long,
        toUs: Long
    ): Double {
        val channels = audio.channelCount
        val from = (fromUs * audio.sampleRate / 1_000_000L).toInt()
        val to = (toUs * audio.sampleRate / 1_000_000L).toInt()
        val count = to - from
        if (count <= 0) return 0.0

        val k = 2.0 * cos(2.0 * Math.PI * PcmOps.BEEP_HZ / audio.sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        var total = 0.0
        for (i in 0 until count) {
            val index = (from + i) * channels
            if (index >= audio.samples.size) break
            val sample = audio.samples[index] / 32768.0
            val s0 = sample + k * s1 - s2
            s2 = s1
            s1 = s0
            total += sample * sample
        }
        val power = s1 * s1 + s2 * s2 - k * s1 * s2
        return if (total > 0) (power / count) / (total / count) else 0.0
    }

    private class Probe(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationUs: Long,
        val hasAudio: Boolean
    )

    private fun probe(file: File): Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var width = 0
            var height = 0
            var rotation = 0
            var durationUs = 0L
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
                when {
                    mime.startsWith("video/") -> {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                        }
                    }

                    mime.startsWith("audio/") -> hasAudio = true
                }
            }
            return Probe(width, height, rotation, durationUs, hasAudio)
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "CensorExport"
    }
}
