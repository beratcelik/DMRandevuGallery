package com.dmrandevu.gallery.media.censor

import com.dmrandevu.gallery.media.censor.ProfanityLexicon.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfanityLexiconTest {

    private val lexicon = ProfanityLexicon()

    private fun profane(word: String) = lexicon.isProfane(word)

    @Test
    fun `catches the root and everything suffixed onto it`() {
        assertTrue(profane("sik"))
        assertTrue(profane("sikeyim"))
        assertTrue(profane("sikeceğim"))
        assertTrue(profane("siktir"))
        assertTrue(profane("sikimde"))
    }

    /**
     * The reason [ProfanityLexicon.normalize] pins the locale. Turkish has a dotted and a dotless
     * i, and the default locale folds the dotless capital onto the dotted lowercase — which turns
     * "SIK" ("squeeze") into the swear word.
     */
    @Test
    fun `keeps the dotted and dotless i apart`() {
        assertTrue(profane("SİK"))
        assertTrue(profane("sikiyor"))

        assertFalse(profane("SIK"))
        assertFalse(profane("sık"))
        assertFalse(profane("sıkıyor"))
        assertFalse(profane("sıkıntı"))
    }

    @Test
    fun `does not beep the word for carrying something`() {
        assertTrue(profane("göt"))
        assertTrue(profane("GÖT"))

        assertFalse(profane("götür"))
        assertFalse(profane("götürdü"))
        assertFalse(profane("götürüyorum"))
        assertFalse(profane("Götür"))
    }

    @Test
    fun `short roots do not swallow ordinary words`() {
        assertTrue(profane("amk"))
        assertTrue(profane("amına"))
        assertTrue(profane("amcık"))

        assertFalse(profane("ama"))
        assertFalse(profane("aman"))
        assertFalse(profane("amca"))
        assertFalse(profane("ambulans"))
        assertFalse(profane("Amerika"))
    }

    @Test
    fun `named exceptions survive their root`() {
        assertFalse(profane("siklon"))
        assertFalse(profane("sikke"))
        assertFalse(profane("gotik"))
    }

    /** Whisper writes some swearing out masked; a masked swear is still a swear. */
    @Test
    fun `masked spellings still match`() {
        assertTrue(profane("s*ktir"))
        assertTrue(profane("a*ına"))
        assertTrue(profane("or*spu"))
        // A mask is only ever written over something that needed masking, so it is read
        // generously: "a*k" and "am*" are both taken as "amk".
        assertTrue(profane("a*k"))
        assertTrue(profane("am*"))

        // The wildcard stands for exactly one character, so a mask of the wrong length cannot
        // stretch to fit a word that has to match end to end.
        assertFalse(profane("a**k"))
    }

    @Test
    fun `punctuation and case do not hide a word`() {
        assertTrue(profane("Siktir!"))
        assertTrue(profane("(orospu)"))
        assertTrue(profane("amk,"))
    }

    @Test
    fun `ordinary speech is left alone`() {
        listOf(
            "merhaba", "araba", "kaynak", "plaka", "trafik", "kamera",
            "buradan", "bekle", "çıkacağım", "kafanı", "kırarım", "koydum",
            "kodlama", "gotham"
        ).forEach { assertFalse(it, profane(it)) }
    }

    @Test
    fun `insults are off unless asked for`() {
        assertFalse(profane("manyak"))
        assertFalse(profane("salak"))

        val both = setOf(Tier.PROFANITY, Tier.INSULT)
        assertTrue(lexicon.isProfane("manyak", both))
        assertTrue(lexicon.isProfane("şerefsiz", both))
        // Still profanity in either setting.
        assertTrue(lexicon.isProfane("siktir", both))
    }

    /**
     * "amına" and "koydum" are both ordinary alone — "koydum" is just "I put it" — and vulgar
     * side by side. Taken from a real clip: "Amına koydum bunu orası seni".
     */
    @Test
    fun `two words that are only profane together`() {
        val words = listOf("Kafanı", "kırarım", "Amına", "koydum", "bunu", "seni")
        assertEquals(setOf(2, 3), lexicon.hits(words))

        assertFalse(profane("koydum"))
        assertEquals(emptySet<Int>(), lexicon.hits(listOf("oraya", "koydum")))
    }

    @Test
    fun `empty and punctuation-only input is not a hit`() {
        assertFalse(profane(""))
        assertFalse(profane("..."))
        assertFalse(profane(" "))
        assertEquals(emptySet<Int>(), lexicon.hits(emptyList()))
    }
}
