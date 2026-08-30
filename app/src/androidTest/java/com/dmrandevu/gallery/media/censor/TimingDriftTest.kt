package com.dmrandevu.gallery.media.censor

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.dmrandevu.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where the word timings come from, and whether they still mean what they say by the time they
 * reach the beep.
 *
 * Diagnostic: the operator reported the beep landing in the wrong place, and the end-to-end test
 * had been too loose to catch it — it asked only that some window overlap the phrase.
 */
@UnstableApi
class TimingDriftTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = CensorModels(context, OkHttpClient())

    @Test
    fun compareOurPipelineAgainstAReferenceDecode() = runBlocking {
        val clip = File("/data/local/tmp/censor_test.mp4")
        val reference = File("/data/local/tmp/bench16k.pcm")
        assumeTrue("no clip", clip.exists())
        assumeTrue("no reference pcm", reference.exists())
        assumeTrue("models missing", models.isInstalled(CensorModels.Model.WHISPER_BASE))

        val audio = AudioTrackDecoder().decode(clip) {}!!
        Log.i(TAG, "decoded: ${audio.frameCount} frames @ ${audio.sampleRate}Hz " +
            "x${audio.channelCount} = ${audio.durationUs / 1000}ms")

        val ours = PcmOps.forRecognition(audio.samples, audio.channelCount, audio.sampleRate, 1f)
        val expected = (audio.durationUs * PcmOps.ASR_SAMPLE_RATE / 1_000_000L).toInt()
        Log.i(TAG, "resampled: ${ours.size} samples, expected $expected, " +
            "ratio ${"%.5f".format(ours.size.toDouble() / expected)}")

        val bytes = reference.readBytes()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val theirs = FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        Log.i(TAG, "reference: ${theirs.size} samples")

        // The point of the comparison: the same audio and the same model, with and without
        // cross-attention alignment.
        var alignedStart = -1L
        val cases = listOf(
            Triple("flash+noalign", WhisperContext.Alignment.NONE, true)
        )
        for ((label, alignment, flash) in cases) {
            val whisper = WhisperContext.load(
                models.fileFor(CensorModels.Model.WHISPER_BASE), alignment, flash
            )
            try {
                val startedAt = System.currentTimeMillis()
                val segments = whisper.transcribe(ours, noTimestamps = false)
                run {
                    Log.i(TAG, "  $label segments=${segments.size} tokens=${segments.sumOf { it.tokens.size }}")
                    segments.flatMap { it.tokens }
                        .filter { it.text.isNotBlank() }
                        .take(10)
                        .forEach { Log.i(TAG, "    tok ${it.text!!.replace(" ", "_")!!} @ ${it.alignedMs}") }
                }
                val words = WordAssembly.fromTokens(segments)
                Log.i(
                    TAG,
                    "$label: ${words.size} words in ${System.currentTimeMillis() - startedAt} ms"
                )
                words.forEachIndexed { i, w ->
                    if (true) {
                        Log.i(TAG, "    [$i] ${w.text} ${w.startUs / 1000}-${w.endUs / 1000}ms")
                    }
                }
                words.filter { it.text.contains("mına", true) }.forEach {
                    Log.i(TAG, "  $label HIT ${it.text} @ ${it.startUs / 1000}ms")
                    if (alignment == WhisperContext.Alignment.BASE) alignedStart = it.startUs / 1000
                }
                // Round numbers are the tell: a token reported exactly on a whole second has
                // almost certainly inherited a window start rather than been placed.
                val round = words.count { it.startUs % 1_000_000L == 0L }
                Log.i(TAG, "  $label on a whole second: $round of ${words.size}")
            } finally {
                whisper.close()
            }
        }

        // Where cutting the audio proves the phrase begins.
        assertTrue("alignment did not place the word at all", alignedStart >= 0)
        assertTrue(
            "aligned start was ${alignedStart}ms, expected near 30680ms",
            abs(alignedStart - 30_680) < 250
        )
    }

    private companion object { const val TAG = "CensorDrift" }
}
