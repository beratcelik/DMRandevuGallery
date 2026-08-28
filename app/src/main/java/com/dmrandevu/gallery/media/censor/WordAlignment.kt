package com.dmrandevu.gallery.media.censor

/**
 * Lines up two transcripts of the same audio, word for word.
 *
 * The recognizer will not give good text and good timings in the same pass. Asked for timings it
 * quietly swaps the swearing for something innocent — the same second of audio that transcribes
 * as "sikeceğim" with timestamps off comes back as "çıkacağım" with them on — and asked for text
 * it answers with one thirty-second block, which is no use for placing a beep.
 *
 * So both passes run, and this puts them side by side: the pass that found the swearing says
 * *what*, the pass that carries timings says *when*. Needleman-Wunsch, because the two transcripts
 * disagree by insertions and deletions as well as swaps, and a positional guess would slide out
 * of step at the first dropped word and beep the wrong second of audio.
 */
object WordAlignment {

    /** One word from each transcript, judged to be the same word. */
    data class Pair(val detectionIndex: Int, val timingIndex: Int)

    /**
     * Pairs of indices into [detection] and [timing], in order.
     *
     * Words neither transcript agrees on are simply left unpaired: an unpaired detection word
     * carries no timing, so it cannot be beeped, and an unpaired timing word was never accused
     * of anything.
     */
    fun align(detection: List<String>, timing: List<String>): List<Pair> {
        val a = detection.map(ProfanityLexicon::normalize)
        val b = timing.map(ProfanityLexicon::normalize)
        if (a.isEmpty() || b.isEmpty()) return emptyList()

        val score = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) score[i][0] = i * GAP
        for (j in 1..b.size) score[0][j] = j * GAP
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                score[i][j] = maxOf(
                    score[i - 1][j - 1] + similarity(a[i - 1], b[j - 1]),
                    score[i - 1][j] + GAP,
                    score[i][j - 1] + GAP
                )
            }
        }

        val pairs = ArrayList<Pair>()
        var i = a.size
        var j = b.size
        while (i > 0 && j > 0) {
            when {
                score[i][j] == score[i - 1][j - 1] + similarity(a[i - 1], b[j - 1]) -> {
                    pairs.add(Pair(i - 1, j - 1))
                    i--
                    j--
                }

                score[i][j] == score[i - 1][j] + GAP -> i--
                else -> j--
            }
        }
        return pairs.reversed()
    }

    /**
     * How alike two words are.
     *
     * A swapped word still has to pair up with the word it replaced, or the swearing never
     * receives a timing. The two are usually near-homophones the recognizer chose between —
     * "sikeceğim" against "çıkacağım" — so a shared run of letters at the front earns partial
     * credit, and only words with nothing in common are pushed apart.
     */
    private fun similarity(a: String, b: String): Int {
        if (a == b) return MATCH
        val shared = a.zip(b).takeWhile { (x, y) -> x == y }.count()
        val shorter = minOf(a.length, b.length)
        return if (shared >= maxOf(2, shorter / 2)) NEAR else MISMATCH
    }

    private const val MATCH = 2
    private const val NEAR = 1
    private const val MISMATCH = -1
    private const val GAP = -1
}
