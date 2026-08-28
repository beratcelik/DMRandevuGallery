package com.dmrandevu.gallery.media.censor

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** The parts of [PcmOps] that are pure arithmetic; the Sonic-backed ones need a device. */
@UnstableApi
class PcmOpsTest {

    private val rate = 44_100

    @Test
    fun `downmix averages the channels`() {
        val stereo = shortArrayOf(100, 300, -200, 0, 1000, 2000)
        val mono = PcmOps.downmixToMono(stereo, 2)
        assertEquals(listOf<Short>(200, -100, 1500), mono.toList())
    }

    @Test
    fun `downmix leaves mono alone`() {
        val mono = shortArrayOf(1, 2, 3)
        assertTrue(PcmOps.downmixToMono(mono, 1) === mono)
    }

    @Test
    fun `deinterleave and interleave round-trip`() {
        val stereo = shortArrayOf(1000, -1000, 2000, -2000, 3000, -3000)
        val channels = PcmOps.deinterleave(stereo, 2)
        assertEquals(2, channels.size)
        assertEquals(3, channels[0].size)
        val back = PcmOps.interleave(channels)
        stereo.indices.forEach { assertEquals(stereo[it].toInt(), back[it].toInt()) }
    }

    @Test
    fun `interleave clamps instead of wrapping round`() {
        // Adding a beep on top of loud background overshoots; wrapping would be a loud crack.
        val channels = arrayOf(floatArrayOf(4f), floatArrayOf(-4f))
        val out = PcmOps.interleave(channels)
        assertEquals(32767, out[0].toInt())
        assertEquals(-32768, out[1].toInt())
    }

    @Test
    fun `the beep is a one kilohertz tone`() {
        val frames = rate / 2
        val channel = FloatArray(frames)
        PcmOps.mixBeepInto(arrayOf(channel), 0, frames, rate)

        // Count zero crossings over the steady middle, away from the fades.
        val from = frames / 4
        val to = frames * 3 / 4
        var crossings = 0
        for (i in from + 1 until to) {
            if (channel[i - 1] < 0f && channel[i] >= 0f) crossings++
        }
        val seconds = (to - from).toFloat() / rate
        val measured = crossings / seconds
        assertTrue("measured ${measured}Hz", abs(measured - PcmOps.BEEP_HZ) < 20)
    }

    @Test
    fun `the beep eases in and out instead of clicking`() {
        val frames = rate / 2
        val channel = FloatArray(frames)
        PcmOps.mixBeepInto(arrayOf(channel), 0, frames, rate)

        assertTrue("starts at ${channel[0]}", abs(channel[0]) < 0.01f)
        assertTrue("ends at ${channel[frames - 1]}", abs(channel[frames - 1]) < 0.01f)

        // No sample-to-sample jump big enough to hear as a click.
        val biggestStep = (1 until frames).maxOf { abs(channel[it] - channel[it - 1]) }
        assertTrue("biggest step $biggestStep", biggestStep < 0.1f)
    }

    @Test
    fun `the beep is added to the background rather than replacing it`() {
        val frames = 4_410
        val background = FloatArray(frames) { 0.1f }
        val channel = background.copyOf()
        PcmOps.mixBeepInto(arrayOf(channel), 0, frames, rate)

        // Compared over a stretch rather than one sample: the tone crosses zero regularly, and a
        // single sample can legitimately sit exactly on a crossing.
        val swing = (frames / 4 until frames / 2).maxOf { abs(channel[it] - background[it]) }
        assertTrue("no tone was added, swing $swing", swing > 0.1f)
        // Averaged over a whole number of cycles the tone sums to nothing, leaving the background.
        val mean = channel.average()
        assertTrue("mean $mean", abs(mean - 0.1) < 0.01)
    }

    @Test
    fun `the beep only touches the frames it was given`() {
        val channel = FloatArray(1_000)
        PcmOps.mixBeepInto(arrayOf(channel), 400, 600, rate)
        (0 until 400).forEach { assertEquals(0f, channel[it], 1e-6f) }
        (600 until 1_000).forEach { assertEquals(0f, channel[it], 1e-6f) }
        assertTrue(channel.copyOfRange(400, 600).any { abs(it) > 0.01f })
    }

    @Test
    fun `beep level is near the level it claims`() {
        val frames = rate / 2
        val channel = FloatArray(frames)
        PcmOps.mixBeepInto(arrayOf(channel), 0, frames, rate)
        val steady = channel.copyOfRange(frames / 4, frames * 3 / 4)
        val rms = sqrt(steady.sumOf { (it * it).toDouble() } / steady.size)
        // A sine's RMS is its peak over root two.
        assertEquals(PcmOps.BEEP_LEVEL / sqrt(2.0), rms, 0.02)
    }

    @Test
    fun `crossfade meets the original exactly at the edges`() {
        val frames = 1_000
        val original = arrayOf(FloatArray(frames) { 0.5f })
        val patched = arrayOf(FloatArray(frames) { -0.5f })
        PcmOps.crossfadeEdges(original, patched, frames = 100)

        assertEquals(0.5f, patched[0][0], 1e-6f)
        assertEquals(0.5f, patched[0][frames - 1], 1e-6f)
        // Untouched in the middle.
        assertEquals(-0.5f, patched[0][frames / 2], 1e-6f)
    }

    @Test
    fun `crossfade is monotonic, so the join cannot be heard as a step`() {
        val frames = 1_000
        val original = arrayOf(FloatArray(frames) { 1f })
        val patched = arrayOf(FloatArray(frames) { 0f })
        PcmOps.crossfadeEdges(original, patched, frames = 100)

        for (i in 1 until 100) {
            assertTrue("rose at $i", patched[0][i] <= patched[0][i - 1] + 1e-6f)
        }
    }

    @Test
    fun `crossfade wider than the patch does not run off either end`() {
        val original = arrayOf(FloatArray(10) { 1f })
        val patched = arrayOf(FloatArray(10) { 0f })
        PcmOps.crossfadeEdges(original, patched, frames = 500)
        assertEquals(10, patched[0].size)
    }

    @Test
    fun `a tone survives being taken apart and put back together`() {
        val frames = 2_000
        val tone = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2.0 * PI * 440 * i / rate) * 8000).toInt().toShort()
            tone[i * 2] = v
            tone[i * 2 + 1] = v
        }
        val back = PcmOps.interleave(PcmOps.deinterleave(tone, 2))
        val worst = tone.indices.maxOf { abs(tone[it] - back[it]) }
        assertTrue("worst sample differs by $worst", worst <= 1)
    }
}
