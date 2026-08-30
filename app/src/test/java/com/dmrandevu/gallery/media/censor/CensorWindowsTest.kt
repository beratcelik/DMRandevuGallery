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
        // The pad at the front, the pad plus the reach at the back — the recognizer's timings
        // run early, so the far edge has to allow for it.
        assertEquals(1_500_000L + CensorWindows.SHIFT_ALLOWANCE_US + 120_000L, windows[0].endUs)
    }

    @Test
    fun `merges words close enough that two beeps would stutter`() {
        // Real timings: "Amına" 30.68-31.17, "koydum" 31.17-31.78 — one beep, not two.
        val words = listOf(word("Amına", 30_680, 31_170), word("koydum", 31_170, 31_780))
        val windows = CensorWindows.build(words, setOf(0, 1), minute)
        assertEquals(1, windows.size)
        assertEquals(30_560_000L, windows[0].startUs)
        assertEquals(
            31_780_000L + CensorWindows.SHIFT_ALLOWANCE_US + 120_000L,
            windows[0].endUs
        )
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

        // Further apart than it used to need to be: a window now reaches up to 700 ms past the
        // word's reported end to find the word itself, so two beeps have to clear that too.
        val far = listOf(word("a", 1_000, 1_100), word("b", 3_000, 3_100))
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
        assertEquals(
            1_600_000L + CensorWindows.SHIFT_ALLOWANCE_US + 120_000L,
            windows[0].endUs
        )
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

    /**
     * The window runs well past the word's reported end, because that is where the word is.
     *
     * The recognizer runs early on the phone this ships to: it placed a phrase at 30.00-30.97 s
     * that really sits at 30.68-31.78 s. Ending the beep where the word reportedly ends stops in
     * the middle of the swearing, which is what the operator heard.
     */
    @Test
    fun `the window reaches past the reported end`() {
        val words = listOf(word("sikeyim", 1_000, 1_300))
        val windows = CensorWindows.build(words, setOf(0), minute, padUs = 0)

        assertEquals(1, windows.size)
        assertEquals(1_300_000L + CensorWindows.SHIFT_ALLOWANCE_US, windows[0].endUs)
    }

    /**
     * The reach is a flat allowance, not "up to the next word".
     *
     * Bounding it by the next word was the first attempt and did not work: that word's timing is
     * shifted early by the same error, so the bound moved with the fault instead of correcting
     * it and the beep still stopped short.
     */
    @Test
    fun `a near next word does not shorten the reach`() {
        val near = listOf(word("sikeyim", 1_000, 1_300), word("seni", 1_300, 1_450))
        val far = listOf(word("sikeyim", 1_000, 1_300), word("sonra", 9_000, 9_400))

        assertEquals(
            CensorWindows.build(near, setOf(0), minute, padUs = 0).first().endUs,
            CensorWindows.build(far, setOf(0), minute, padUs = 0).first().endUs
        )
    }
}
