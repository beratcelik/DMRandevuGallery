package com.dmrandevu.gallery.media

import com.dmrandevu.gallery.media.watermark.WanderPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class WanderPathTest {

    private val seconds = (0..6_000).map { it * 0.05 } // five minutes at 20 samples a second

    @Test
    fun `the label never leaves the frame`() {
        repeat(200) { seed ->
            val path = WanderPath(Random(seed))
            seconds.forEach { t ->
                assertTrue(abs(path.x(t)) <= 1.0 && abs(path.y(t)) <= 1.0)
            }
        }
    }

    @Test
    fun `videos do not start in the same place`() {
        val starts = (0 until 50).map { WanderPath(Random(it)).let { p -> p.x(0.0) to p.y(0.0) } }
        assertEquals(starts.size, starts.toSet().size)
        // Spread across the frame, not bunched: some start on each side of the centre.
        assertTrue(starts.any { it.first < -0.3 } && starts.any { it.first > 0.3 })
        assertTrue(starts.any { it.second < -0.3 } && starts.any { it.second > 0.3 })
    }

    @Test
    fun `it drifts rather than jumps`() {
        repeat(200) { seed ->
            val path = WanderPath(Random(seed))
            seconds.zipWithNext().forEach { (a, b) ->
                val step = hypot(path.x(b) - path.x(a), path.y(b) - path.y(a))
                // Offsets are in half-frames. The fastest the weights allow is about 0.51 a
                // second (0.41 vertically and 0.31 across at once), so 0.55 only fails on a
                // path that has actually got quicker than designed.
                assertTrue("seed $seed moved $step at $a", step / (b - a) < 0.55)
            }
        }
    }

    @Test
    fun `it visits the frame rather than staying near the centre`() {
        val path = WanderPath(Random(7))
        assertTrue(seconds.maxOf { abs(path.x(it)) } > 0.7)
        assertTrue(seconds.maxOf { abs(path.y(it)) } > 0.7)
    }
}
