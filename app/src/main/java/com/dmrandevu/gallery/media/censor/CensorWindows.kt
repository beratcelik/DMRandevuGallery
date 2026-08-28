package com.dmrandevu.gallery.media.censor

/** One word the recognizer heard, and when it heard it. */
data class TimedWord(val text: String, val startUs: Long, val endUs: Long)

/** A stretch of audio to beep over. */
data class CensorWindow(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = endUs - startUs
}

/**
 * Turns the words that matched the lexicon into the stretches of audio to beep.
 *
 * Word timings are close but not exact — the recognizer places the boundary somewhere inside the
 * silence around the word rather than on the consonant — so every window is padded. Windows that
 * nearly touch are merged, because two beeps a fifth of a second apart sound like a stutter and
 * the speech between them is a syllable of the same swearing anyway.
 */
object CensorWindows {

    /**
     * [hits] are indices into [words]. [durationUs] bounds the last window so padding cannot run
     * off the end of the audio.
     */
    fun build(
        words: List<TimedWord>,
        hits: Set<Int>,
        durationUs: Long,
        padUs: Long = PAD_US,
        mergeGapUs: Long = MERGE_GAP_US
    ): List<CensorWindow> {
        if (hits.isEmpty()) return emptyList()
        val spans = hits.asSequence()
            .filter { it in words.indices }
            .map { words[it] }
            .map { (it.startUs - padUs).coerceAtLeast(0) to (it.endUs + padUs).coerceAtMost(durationUs) }
            .filter { (start, end) -> end > start }
            .sortedBy { it.first }
            .toList()

        val merged = ArrayList<CensorWindow>()
        for ((start, end) in spans) {
            val last = merged.lastOrNull()
            if (last != null && start - last.endUs < mergeGapUs) {
                merged[merged.size - 1] = CensorWindow(last.startUs, maxOf(last.endUs, end))
            } else {
                merged.add(CensorWindow(start, end))
            }
        }
        return merged
    }

    /**
     * How far either side of the word the beep reaches.
     *
     * Sized against the measured error: cutting the audio at the timings the recognizer reported
     * for "Amına koydum" played back the whole phrase and nothing else, so the timings are good
     * to well inside this. The padding is there for the cases that are not that clean, and for
     * the leading consonant that starts before the recognizer says the word does.
     */
    private const val PAD_US = 120_000L

    /** Two windows closer than this become one, rather than a beep-gap-beep stutter. */
    private const val MERGE_GAP_US = 300_000L
}
