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
        mergeGapUs: Long = MERGE_GAP_US,
        shiftAllowanceUs: Long = SHIFT_ALLOWANCE_US
    ): List<CensorWindow> {
        if (hits.isEmpty()) return emptyList()
        val spans = hits.asSequence()
            .filter { it in words.indices }
            .map { index ->
                val word = words[index]
                // Runs past where the word reportedly ends, by however much is still unaccounted
                // for after calibration.
                //
                // The recognizer's timings run early on this phone. Measured against the same
                // clip cut by hand: it places "Amına koydu mu" at 30.00-30.97 s where the words
                // really are at 30.68-31.78 s, an error of 680 to 810 ms that grows slowly
                // across the phrase. Ending the beep where the word reportedly ends stops in the
                // middle of the swearing, which is what the operator heard.
                //
                // Bounding this by the *next* word's end was an early attempt and does not work:
                // that timing is shifted early too, so the bound moves with the error instead of
                // correcting it.
                //
                // Stretching by the whole error was the next, and it beeps a second of innocent
                // audio before every swear word. [TimingCalibration] measures the offset for the
                // clip instead, so what is left here only has to cover what the measurement
                // missed. When it could not measure, the caller passes the wide allowance back
                // in: too much beep is a nuisance, too little leaves the swearing audible.
                val end = word.endUs + shiftAllowanceUs
                (word.startUs - padUs).coerceAtLeast(0) to (end + padUs).coerceAtMost(durationUs)
            }
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
     * How far past a word's reported end the beep may reach to find the word itself.
     *
     * Sized from the measured error — 680 to 810 ms on the operator's own clip — with enough
     * margin that the end of a phrase, where the drift is largest, is still covered.
     */
    const val SHIFT_ALLOWANCE_US = 900_000L

    /**
     * What is left to cover once the offset has actually been measured: the residual error of
     * the measurement itself, which is a frame or two either way, plus a little for the word
     * boundary.
     */
    const val RESIDUAL_ALLOWANCE_US = 200_000L

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
