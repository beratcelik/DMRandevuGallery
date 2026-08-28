package com.dmrandevu.gallery.media.censor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordAlignmentTest {

    private fun align(a: String, b: String) =
        WordAlignment.align(a.split(" "), b.split(" "))

    @Test
    fun `identical transcripts pair straight across`() {
        val pairs = align("sen bekle bekle buradan dur", "sen bekle bekle buradan dur")
        assertEquals(5, pairs.size)
        pairs.forEach { assertEquals(it.detectionIndex, it.timingIndex) }
    }

    /**
     * The case the whole two-pass design exists for, taken from the operator's own clip: the
     * timestamped pass heard "çıkacağım" where the untimed pass heard "sikeceğim". The swear has
     * to land on that word, or it never gets a time and never gets beeped.
     */
    @Test
    fun `a swapped word still pairs with the word it replaced`() {
        val detection = "Sen ben almadığımı sikeceğim sen bekle bekle Vur lan dur".split(" ")
        val timing = "Sen ben aradığımı çıkacağım sen bekle bekle Buradan dur".split(" ")

        val pairs = WordAlignment.align(detection, timing)
        val swear = pairs.firstOrNull { detection[it.detectionIndex] == "sikeceğim" }
        assertNotNull("the swear must be paired with something", swear)
        assertEquals("çıkacağım", timing[swear!!.timingIndex])
    }

    @Test
    fun `an inserted word does not slide the rest out of step`() {
        val detection = "gel buraya gel".split(" ")
        val timing = "gel hemen buraya gel".split(" ")

        val pairs = WordAlignment.align(detection, timing)
        assertEquals("buraya", timing[pairs.first { detection[it.detectionIndex] == "buraya" }.timingIndex])
        assertEquals(3, pairs.size)
    }

    @Test
    fun `a dropped word does not slide the rest out of step`() {
        val detection = "oğlum kaynak yapıyorsun manyak mısın sen".split(" ")
        val timing = "oğlum yapıyorsun manyak mısın sen".split(" ")

        val pairs = WordAlignment.align(detection, timing)
        val manyak = pairs.first { detection[it.detectionIndex] == "manyak" }
        assertEquals("manyak", timing[manyak.timingIndex])
    }

    @Test
    fun `pairs come back in order`() {
        val pairs = align("bir iki üç dört beş", "bir iki dört beş altı")
        assertEquals(pairs.sortedBy { it.detectionIndex }, pairs)
        assertEquals(pairs.sortedBy { it.timingIndex }, pairs)
    }

    /**
     * The swear must not drift onto a word that is nowhere near it. A transcript that shares
     * nothing with the other one should pair the swear late in the sequence, if at all — never
     * onto the opening word, which is where a naive positional guess would put it.
     */
    @Test
    fun `a swear does not land on an unrelated distant word`() {
        val detection = "merhaba nasılsın iyiyim siktir".split(" ")
        val timing = "merhaba nasılsın iyiyim teşekkürler".split(" ")

        val pairs = WordAlignment.align(detection, timing)
        val swear = pairs.firstOrNull { detection[it.detectionIndex] == "siktir" }
        if (swear != null) {
            assertEquals("teşekkürler", timing[swear.timingIndex])
        }
        // The words both transcripts do share must pair with themselves.
        listOf("merhaba", "nasılsın", "iyiyim").forEach { shared ->
            val pair = pairs.first { detection[it.detectionIndex] == shared }
            assertEquals(shared, timing[pair.timingIndex])
        }
    }

    @Test
    fun `empty input aligns to nothing`() {
        assertTrue(WordAlignment.align(emptyList(), listOf("a")).isEmpty())
        assertTrue(WordAlignment.align(listOf("a"), emptyList()).isEmpty())
        assertTrue(WordAlignment.align(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `casing and punctuation do not stop a pairing`() {
        val pairs = WordAlignment.align(listOf("Siktir!"), listOf("siktir"))
        assertEquals(1, pairs.size)
    }

    /**
     * End to end over the real transcripts: the untimed pass accuses a word, alignment gives it a
     * time, and the window lands where the swearing actually is.
     */
    @Test
    fun `verdict from one pass becomes a window on the other`() {
        val detection = "Sen ben almadığımı sikeceğim sen bekle bekle".split(" ")
        val timing = listOf(
            TimedWord("Sen", 42_500_000, 42_780_000),
            TimedWord("ben", 42_780_000, 43_050_000),
            TimedWord("aradığımı", 43_050_000, 44_030_000),
            TimedWord("çıkacağım", 44_030_000, 44_510_000),
            TimedWord("sen", 44_510_000, 44_710_000),
            TimedWord("bekle", 44_710_000, 44_970_000),
            TimedWord("bekle", 44_970_000, 45_290_000)
        )

        val lexicon = ProfanityLexicon()
        val hits = WordAlignment.align(detection, timing.map { it.text })
            .filter { lexicon.isProfane(detection[it.detectionIndex]) }
            .map { it.timingIndex }
            .toSet()

        assertEquals(setOf(3), hits)

        val windows = CensorWindows.build(timing, hits, durationUs = 60_000_000)
        assertEquals(1, windows.size)
        assertEquals(43_910_000L, windows[0].startUs)
        assertEquals(44_630_000L, windows[0].endUs)
    }
}
