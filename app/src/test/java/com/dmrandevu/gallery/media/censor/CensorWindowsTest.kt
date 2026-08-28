package com.dmrandevu.gallery.media.censor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CensorWindowsTest {

    private fun word(text: String, startMs: Long, endMs: Long) =
        TimedWord(text, startMs * 1_000, endMs * 1_000)

    private val minute = 60_000_000L

    @Test
    fun `pads the word on both sides`() {
        val words = listOf(word("siktir", 1_000, 1_500))
        val windows = CensorWindows.build(words, setOf(0), minute)
        assertEquals(1, windows.size)
        assertEquals(880_000L, windows[0].startUs)
        assertEquals(1_620_000L, windows[0].endUs)
    }

    @Test
    fun `merges words close enough that two beeps would stutter`() {
        // Real timings: "Amına" 30.68-31.17, "koydum" 31.17-31.78 — one beep, not two.
        val words = listOf(word("Amına", 30_680, 31_170), word("koydum", 31_170, 31_780))
        val windows = CensorWindows.build(words, setOf(0, 1), minute)
        assertEquals(1, windows.size)
        assertEquals(30_560_000L, windows[0].startUs)
        assertEquals(31_900_000L, windows[0].endUs)
    }

    @Test
    fun `leaves words far apart as separate beeps`() {
        val words = listOf(word("siktir", 1_000, 1_200), word("orospu", 5_000, 5_400))
        val windows = CensorWindows.build(words, setOf(0, 1), minute)
        assertEquals(2, windows.size)
    }

    /** The gap is measured between the padded edges, not the words. */
    @Test
    fun `merge threshold applies after padding`() {
        val near = listOf(word("a", 1_000, 1_100), word("b", 1_350, 1_450))
        assertEquals(1, CensorWindows.build(near, setOf(0, 1), minute).size)

        val far = listOf(word("a", 1_000, 1_100), word("b", 1_900, 2_000))
        assertEquals(2, CensorWindows.build(far, setOf(0, 1), minute).size)
    }

    @Test
    fun `padding cannot run off either end of the audio`() {
        val words = listOf(word("siktir", 0, 200), word("amk", 9_900, 10_000))
        val windows = CensorWindows.build(words, setOf(0, 1), durationUs = 10_000_000)
        assertEquals(0L, windows.first().startUs)
        assertEquals(10_000_000L, windows.last().endUs)
    }

    @Test
    fun `no hits means no windows`() {
        val words = listOf(word("merhaba", 1_000, 1_500))
        assertTrue(CensorWindows.build(words, emptySet(), minute).isEmpty())
        assertTrue(CensorWindows.build(emptyList(), emptySet(), minute).isEmpty())
    }

    @Test
    fun `hits are ordered even when the indices are not`() {
        val words = listOf(
            word("a", 5_000, 5_200), word("b", 1_000, 1_200), word("c", 3_000, 3_200)
        )
        val windows = CensorWindows.build(words, setOf(0, 2, 1), minute)
        assertEquals(3, windows.size)
        assertEquals(windows.sortedBy { it.startUs }, windows)
    }

    @Test
    fun `overlapping padded windows coalesce`() {
        val words = listOf(word("a", 1_000, 1_400), word("b", 1_200, 1_600))
        val windows = CensorWindows.build(words, setOf(0, 1), minute)
        assertEquals(1, windows.size)
        assertEquals(880_000L, windows[0].startUs)
        assertEquals(1_720_000L, windows[0].endUs)
    }

    @Test
    fun `an index the word list does not have is ignored`() {
        val words = listOf(word("siktir", 1_000, 1_200))
        val windows = CensorWindows.build(words, setOf(0, 7), minute)
        assertEquals(1, windows.size)
    }

    /** A window whose padding gets clamped to nothing must not become an inside-out span. */
    @Test
    fun `zero-length audio produces no window`() {
        val words = listOf(word("siktir", 1_000, 1_200))
        assertTrue(CensorWindows.build(words, setOf(0), durationUs = 0).isEmpty())
    }
}
