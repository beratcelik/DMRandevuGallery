package com.dmrandevu.gallery.media.censor

import java.util.Locale

/**
 * Which Turkish words get beeped.
 *
 * Turkish is agglutinative — a root takes a queue of suffixes, so "sik" turns up as "sikeyim",
 * "sikeceğim", "siktir", "sikimde" and dozens more. Listing forms would never finish, so most
 * entries match on the root as a prefix. That cuts the other way too: a prefix is exactly how
 * "göt" swallows "götürdü" ("he took it"), which is why entries carry their own exceptions
 * rather than relying on the list being lucky.
 *
 * Matching is deliberately exact, never fuzzy. Whisper hallucinates whole phrases over music
 * ("Altyazı M.K.", "İzlediğiniz için teşekkür ederim"), and a fuzzy matcher would beep them.
 */
class ProfanityLexicon(private val entries: List<Entry> = DEFAULT) {

    /**
     * How offensive a word is, so the operator can beep swearing without beeping every insult.
     *
     * "manyak mısın sen" ("are you crazy") turned up in testing on a roadside argument. It is an
     * insult, but beeping it censors ordinary angry speech, so it does not travel with [PROFANITY].
     */
    enum class Tier { PROFANITY, INSULT }

    enum class Mode {
        /** The whole word must be the entry. For roots too short to be safe as a prefix. */
        EXACT,

        /** The word starts with the entry — the suffix queue can be anything. */
        PREFIX
    }

    data class Entry(
        val root: String,
        val mode: Mode,
        val tier: Tier = Tier.PROFANITY,
        /** Words that start with [root] but are innocent, and must not match. */
        val exceptions: List<String> = emptyList()
    )

    /**
     * Two words that are only profane together. "amına" and "koydum" are both ordinary on their
     * own; side by side they are the commonest curse in the language.
     */
    data class Phrase(val first: String, val second: String)

    /**
     * Whether [word] as the recognizer wrote it should be beeped. [tiers] says which tiers count.
     */
    fun isProfane(word: String, tiers: Set<Tier> = setOf(Tier.PROFANITY)): Boolean {
        val normalized = normalize(word)
        if (normalized.isEmpty()) return false
        return entries.any { entry ->
            entry.tier in tiers && when (entry.mode) {
                Mode.EXACT -> matches(normalized, entry.root)
                Mode.PREFIX -> startsWith(normalized, entry.root) &&
                    entry.exceptions.none { startsWith(normalized, it) }
            }
        }
    }

    /**
     * Indices in [words] to beep, counting both single words and the two-word phrases.
     */
    fun hits(words: List<String>, tiers: Set<Tier> = setOf(Tier.PROFANITY)): Set<Int> {
        val hits = LinkedHashSet<Int>()
        val normalized = words.map(::normalize)
        for (i in words.indices) {
            if (isProfane(words[i], tiers)) hits.add(i)
        }
        for (i in 0 until words.size - 1) {
            for (phrase in PHRASES) {
                if (startsWith(normalized[i], phrase.first) &&
                    startsWith(normalized[i + 1], phrase.second)
                ) {
                    hits.add(i)
                    hits.add(i + 1)
                }
            }
        }
        return hits
    }

    companion object {

        /**
         * Turkish lowercase, punctuation stripped, `*` kept.
         *
         * The locale is not optional. Turkish has two i's, and the default locale lowercases the
         * dotless capital `I` to a dotted `i` — which turns "SIK" ("squeeze", "often") into "sik"
         * and beeps a perfectly ordinary word. `Locale("tr")` maps `I`→`ı` and `İ`→`i`, keeping
         * the two apart.
         */
        fun normalize(word: String): String = word
            .lowercase(Locale("tr"))
            .filter { it.isLetterOrDigit() || it == '*' }

        /**
         * Equality where `*` in [word] stands for any single character.
         *
         * Whisper sometimes writes profanity out masked — "s*ktir" — and a masked swear is still
         * a swear that has to be beeped.
         */
        private fun matches(word: String, root: String): Boolean {
            if (word.length != root.length) return false
            return word.indices.all { word[it] == '*' || word[it] == root[it] }
        }

        private fun startsWith(word: String, root: String): Boolean =
            word.length >= root.length && matches(word.take(root.length), root)

        private val PHRASES = listOf(
            Phrase("amina", "koy"), Phrase("amına", "koy"),
            Phrase("amina", "kod"), Phrase("amına", "kod"),
            Phrase("anani", "sik"), Phrase("ananı", "sik"),
            Phrase("ananin", "am"), Phrase("ananın", "am"),
            Phrase("avradini", "sik"), Phrase("avradını", "sik"),
            Phrase("orospu", "çocu"), Phrase("orospu", "cocu"),
            Phrase("orospu", "evla"),
            Phrase("it", "oğlu")
        )

        /**
         * The list itself. Kept in one place so it can be read and argued with; the operator sees
         * the effect of every line the moment a video is exported.
         */
        val DEFAULT: List<Entry> = listOf(
            // "am" alone is unusable as a prefix — ama, aman, amca, ambulans, Amerika all start
            // with it. Only the forms that cannot be anything else.
            Entry("amk", Mode.EXACT), Entry("amq", Mode.EXACT), Entry("aq", Mode.EXACT),
            Entry("amina", Mode.PREFIX), Entry("amına", Mode.PREFIX),
            Entry("amcik", Mode.PREFIX), Entry("amcık", Mode.PREFIX),
            Entry("amcigi", Mode.PREFIX), Entry("amcığı", Mode.PREFIX),

            // sik-: the dotted i is the profanity, the dotless ı ("sık") is not, and normalize()
            // is what keeps them apart. "siklon" (cyclone) and "sikke" (coin) are innocent.
            Entry("sik", Mode.PREFIX, exceptions = listOf("siklon", "sikke", "sikloid")),
            Entry("siktir", Mode.PREFIX),

            // göt-: "götür-" (to carry) is the whole reason exceptions exist.
            Entry("göt", Mode.PREFIX, exceptions = listOf("götür")),
            Entry("got", Mode.PREFIX, exceptions = listOf("gotur", "gotham", "gotik")),

            Entry("orospu", Mode.PREFIX), Entry("kahpe", Mode.PREFIX),
            Entry("piç", Mode.PREFIX), Entry("pic", Mode.EXACT),
            Entry("yarrak", Mode.PREFIX), Entry("yarak", Mode.PREFIX),
            Entry("yarra", Mode.PREFIX),
            Entry("pezevenk", Mode.PREFIX), Entry("gavat", Mode.PREFIX),
            Entry("kaltak", Mode.PREFIX), Entry("sürtük", Mode.PREFIX),
            Entry("surtuk", Mode.PREFIX),
            Entry("puşt", Mode.PREFIX), Entry("pust", Mode.EXACT),
            Entry("yavşak", Mode.PREFIX), Entry("yavsak", Mode.PREFIX),
            Entry("ibne", Mode.PREFIX), Entry("ibno", Mode.PREFIX),
            Entry("avrat", Mode.PREFIX),

            // koy-/kod- as the vulgar verb. The innocent senses of "koymak" are far too common to
            // prefix-match, so only the forms that are vulgar on their own.
            Entry("koyim", Mode.EXACT), Entry("koyum", Mode.EXACT),
            Entry("koyayim", Mode.EXACT), Entry("koyayım", Mode.EXACT),
            Entry("kodumun", Mode.PREFIX), Entry("koduğumun", Mode.PREFIX),
            Entry("kodugumun", Mode.PREFIX), Entry("koyduğumun", Mode.PREFIX),
            Entry("koydugumun", Mode.PREFIX),

            // Insults: real, but beeping them censors ordinary shouting. Off unless asked for.
            Entry("şerefsiz", Mode.PREFIX, Tier.INSULT),
            Entry("serefsiz", Mode.PREFIX, Tier.INSULT),
            Entry("namussuz", Mode.PREFIX, Tier.INSULT),
            Entry("haysiyetsiz", Mode.PREFIX, Tier.INSULT),
            Entry("manyak", Mode.PREFIX, Tier.INSULT),
            Entry("salak", Mode.PREFIX, Tier.INSULT),
            Entry("aptal", Mode.PREFIX, Tier.INSULT),
            Entry("gerizekali", Mode.PREFIX, Tier.INSULT),
            Entry("gerizekalı", Mode.PREFIX, Tier.INSULT),
            Entry("dangalak", Mode.PREFIX, Tier.INSULT),
            Entry("angut", Mode.PREFIX, Tier.INSULT),
            Entry("hayvan", Mode.PREFIX, Tier.INSULT),
            Entry("eşşek", Mode.PREFIX, Tier.INSULT),
            Entry("essek", Mode.PREFIX, Tier.INSULT)
        )
    }
}
