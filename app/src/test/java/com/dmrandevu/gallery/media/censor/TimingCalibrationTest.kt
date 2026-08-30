package com.dmrandevu.gallery.media.censor

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Built on synthetic audio where the true offset is known, because on real audio it is not. */
@UnstableApi
class TimingCalibrationTest {

    private val rate = 16_000

    /**
     * Audio that is loud exactly during [speech] and quiet elsewhere.
     *
     * Quiet rather than silent: a real clip has traffic and music under the talking, and a
     * measurement that only works against digital silence would not survive contact with one.
     */
    private fun audio(
        durationMs: Int,
        speech: List<IntRange>,
        floor: Float = 0.05f
    ): AudioTrackDecoder.DecodedAudio {
        val random = Random(4)
        val frames = durationMs * rate / 1000
        val samples = ShortArray(frames)
        for (i in 0 until frames) {
            val ms = i * 1000 / rate
            val loud = speech.any { ms in it }
            val level = if (loud) 0.5f else floor
            val value = level * sin(2.0 * PI * 220 * i / rate).toFloat() +
                (random.nextFloat() - 0.5f) * floor
            samples[i] = (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return AudioTrackDecoder.DecodedAudio(samples, rate, 1)
    }

    private fun word(fromMs: Long, toMs: Long) =
        TimedWord("w", fromMs * 1_000, toMs * 1_000)

    @Test
    fun `finds an offset that is really there`() {
        // Sound at 3.0-4.0s and 6.0-7.0s; the recognizer claims 700 ms earlier than each.
        val clip = audio(10_000, listOf(3_000..4_000, 6_000..7_000))
        val words = listOf(word(2_300, 3_300), word(5_300, 6_300))

        val result = TimingCalibration.estimate(clip, words)
        assertTrue("not confident", result.confident)
        assertEquals(700_000.0, result.shiftUs.toDouble(), 60_000.0)
    }

    @Test
    fun `reports no offset when the timings already fit`() {
        val clip = audio(10_000, listOf(3_000..4_000, 6_000..7_000))
        val words = listOf(word(3_000, 4_000), word(6_000, 7_000))

        val result = TimingCalibration.estimate(clip, words)
        assertTrue(result.confident)
        assertTrue("shift was ${result.shiftUs}", abs(result.shiftUs) < 80_000)

    }

    @Test
    fun `is not confident about a clip that is loud throughout`() {
        // Nothing to line up against: every offset fits equally well.
        val clip = audio(10_000, listOf(0..10_000))
        val words = listOf(word(2_000, 3_000), word(5_000, 6_000))
        assertFalse(TimingCalibration.estimate(clip, words).confident)
    }

    /**
     * The case that matters, and the one that caught this out.
     *
     * The recognizer reports its words back to back, so on a talkative clip its idea of "speech"
     * is one unbroken block with nothing to align. On the operator's own video that block covered
     * 80% of the audio and the measurement came back a confident 240 ms when the real error was
     * nearer 700 ms — which shortened the window and left the end of the swearing audible.
     */
    @Test
    fun `refuses a clip whose words leave no gaps`() {
        val clip = audio(10_000, listOf(500..9_500))
        val backToBack = (0 until 9).map { word(it * 1_000L + 500, it * 1_000L + 1_500) }
        assertFalse(TimingCalibration.estimate(clip, backToBack).confident)
    }

    @Test
    fun `is not confident when the recognizer heard almost nothing`() {
        val clip = audio(10_000, listOf(3_000..4_000))
        val words = listOf(word(3_000, 3_100))
        assertFalse(TimingCalibration.estimate(clip, words).confident)
    }

    @Test
    fun `says nothing at all about an empty clip`() {
        val empty = AudioTrackDecoder.DecodedAudio(ShortArray(0), rate, 1)
        val result = TimingCalibration.estimate(empty, listOf(word(0, 100)))
        assertFalse(result.confident)
        assertEquals(0L, result.shiftUs)
    }

    @Test
    fun `says nothing when there are no words`() {
        val clip = audio(4_000, listOf(1_000..2_000))
        assertFalse(TimingCalibration.estimate(clip, emptyList()).confident)
    }

    @Test
    fun `never answers with a negative offset`() {
        // The recognizer running late has never been observed, and allowing it would let a noisy
        // clip drag the beep backwards off the word.
        val clip = audio(10_000, listOf(3_000..4_000, 6_000..7_000))
        val words = listOf(word(3_700, 4_700), word(6_700, 7_700))
        assertTrue(TimingCalibration.estimate(clip, words).shiftUs >= 0)
    }

    @Test
    fun `will not report an offset beyond the range it searched`() {
        val clip = audio(12_000, listOf(8_000..9_000))
        val words = listOf(word(1_000, 2_000), word(3_000, 4_000))
        val result = TimingCalibration.estimate(clip, words, maxShiftUs = 400_000)
        assertTrue(result.shiftUs <= 400_000)
        // Pinned to the edge of the search means the real answer is past it, so it is not trusted.
        if (result.shiftUs == 400_000L) assertFalse(result.confident)
    }
}
