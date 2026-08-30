package com.dmrandevu.gallery.media.censor

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.dmrandevu.whisper.WhisperContext

/**
 * Asks the recognizer again, about a few seconds at a time, to find out when the swearing really
 * happens.
 *
 * Recognising the whole clip gives the right words at the wrong times: whisper works in
 * thirty-second windows and a word early in a window is dragged towards the window's start. On
 * the operator's clip that put "Amına koydu mu" at 30.00 s when it is at 30.68 s, and the beep
 * had to be stretched by a second to be sure of covering it.
 *
 * A few seconds of audio is a single window, and a word in the middle of it has no boundary to be
 * pulled to. Measured on the same clip: an eight-second snippet places the word at 30.72 s, forty
 * milliseconds from the truth. So the first pass says *what* and roughly where, and this says
 * *when*.
 *
 * Cross-attention alignment was tried here too and is worse — 30.94 s on the same snippet — as
 * well as costing the transcription, so the snippet pass is a plain one.
 */
@UnstableApi
class TimingRefiner(
    private val lexicon: ProfanityLexicon,
    private val transcribe: suspend (FloatArray) -> List<WhisperContext.Segment>
) {

    /** A stretch of swearing, as precisely as it can be placed. */
    data class Refined(val startUs: Long, val endUs: Long)

    /**
     * Re-times each run of consecutive hit words in [words].
     *
     * Returns null for a run the second pass could not confirm — the caller keeps the rough
     * timing and the wide window that goes with it, rather than trusting a guess.
     */
    suspend fun refine(
        audio: AudioTrackDecoder.DecodedAudio,
        words: List<TimedWord>,
        hits: Set<Int>,
        tiers: Set<ProfanityLexicon.Tier>
    ): Map<IntRange, Refined?> {
        val result = LinkedHashMap<IntRange, Refined?>()
        for (run in runsOf(hits)) {
            val roughStart = words[run.first].startUs
            val roughEnd = words[run.last].endUs
            result[run] = refineOne(audio, roughStart, roughEnd, tiers)
        }
        return result
    }

    private suspend fun refineOne(
        audio: AudioTrackDecoder.DecodedAudio,
        roughStartUs: Long,
        roughEndUs: Long,
        tiers: Set<ProfanityLexicon.Tier>
    ): Refined? {
        // Reaches further forward than back: the first pass reports early, so the word is after
        // where it was said to be, not before.
        //
        // And never a short snippet. Below about eight seconds whisper's timestamps stop
        // spreading out and pile up on the last instant of the audio — measured at 5.3 s, six
        // words in a row came back at exactly the snippet's end, which put the beep two and a
        // half seconds past the swearing.
        var from = (roughStartUs - LEAD_US).coerceAtLeast(0)
        var to = (roughEndUs + TRAIL_US).coerceAtMost(audio.durationUs)
        if (to - from < MIN_SNIPPET_US) {
            to = (from + MIN_SNIPPET_US).coerceAtMost(audio.durationUs)
            from = (to - MIN_SNIPPET_US).coerceAtLeast(0)
        }
        if (to - from < MIN_SNIPPET_US) return null

        val snippet = cut(audio, from, to)
        val heard = WordAssembly.fromTokens(transcribe(snippet))
        if (heard.isEmpty()) return null

        Log.i(
            TAG,
            "snippet ${from / 1000}-${to / 1000}ms (rough ${roughStartUs / 1000}-" +
                "${roughEndUs / 1000}): " +
                heard.joinToString { "${it.text}@${(from + it.startUs) / 1000}" }
        )
        val found = lexicon.hits(heard.map { it.text }, tiers)
        if (found.isEmpty()) return null

        // Whether the second pass actually placed anything is checked below rather than
        // assumed. Its timestamps do not always spread across the snippet: on some hardware they
        // pile onto the first or last instant of it, and a "refinement" that returns the whole
        // snippet is worse than the rough timing it replaced, because it would beep six seconds.
        val start = found.minOf { heard[it].startUs }
        // Carried to the end of the word after the last one flagged. The second pass splits
        // words where the lexicon does not: "koydum" comes back as "koydu" and "mu?", and only
        // the first half is profane, so stopping at it stops halfway through the swearing.
        val lastHit = found.max()
        val next = heard.getOrNull(lastHit + 1)
        val spoken = heard[lastHit].endUs
        val end = if (next != null) {
            minOf(next.endUs, spoken + TAIL_REACH_US)
        } else {
            spoken
        }
        val span = to - from

        val atStart = start <= SATURATION_MARGIN_US
        val atEnd = end >= span - SATURATION_MARGIN_US
        val rough = roughEndUs - roughStartUs
        val tooLong = (end - start) > rough * IMPLAUSIBLE_FACTOR + IMPLAUSIBLE_SLACK_US
        if (atStart || atEnd || tooLong) {
            Log.i(
                TAG,
                "second pass placed the words at ${start / 1000}-${end / 1000}ms of a " +
                    "${span / 1000}ms snippet; not usable, keeping the rough timing"
            )
            return null
        }

        // Everything profane in the snippet, taken together. A run of swearing comes back as
        // several words and they belong in one beep; anything else nearby wants covering anyway.
        Log.i(TAG, "refined to ${(from + start) / 1000}-${(from + end) / 1000}ms")
        return Refined(from + start, from + end)
    }

    /** Consecutive hit indices belong to one phrase and are re-timed together. */
    private fun runsOf(hits: Set<Int>): List<IntRange> {
        val sorted = hits.sorted()
        val runs = ArrayList<IntRange>()
        var first = -1
        var last = -1
        for (index in sorted) {
            if (first < 0) {
                first = index
                last = index
            } else if (index == last + 1) {
                last = index
            } else {
                runs.add(first..last)
                first = index
                last = index
            }
        }
        if (first >= 0) runs.add(first..last)
        return runs
    }

    /** 16 kHz mono floats for one stretch of the decoded track. */
    private fun cut(
        audio: AudioTrackDecoder.DecodedAudio,
        fromUs: Long,
        toUs: Long
    ): FloatArray {
        val channels = audio.channelCount
        val from = (fromUs * audio.sampleRate / 1_000_000L).toInt()
        val to = (toUs * audio.sampleRate / 1_000_000L).toInt()
            .coerceAtMost(audio.frameCount.toInt())
        val slice = ShortArray(((to - from).coerceAtLeast(0)) * channels)
        for (i in slice.indices) {
            val at = from * channels + i
            slice[i] = if (at < audio.samples.size) audio.samples[at] else 0
        }
        return PcmOps.forRecognition(slice, channels, audio.sampleRate, 1f)
    }

    companion object {
        private const val TAG = "CensorRefine"

        /**
         * How far back the snippet starts from where the first pass put the word.
         *
         * Deliberately short. The word is *after* where the first pass put it, never before, and
         * every extra second of speech dragged in at the front is another second whisper has to
         * place before it reaches the part that matters — which is when the timestamps give up
         * and pile onto the last instant.
         */
        const val LEAD_US = 2_000_000L

        /** And well past it, because the first pass reports early. */
        const val TRAIL_US = 6_000_000L

        /**
         * Below about six seconds the timestamps saturate rather than spread: measured at 5.3 s,
         * six words in a row came back at exactly the snippet's end.
         */
        const val MIN_SNIPPET_US = 6_000_000L

        /**
         * How far past the last flagged word the beep may run to catch the rest of it.
         *
         * Small, because this is applied to a timing that has already been measured properly —
         * unlike the defensive allowance used when the second pass cannot place anything.
         */
        private const val TAIL_REACH_US = 700_000L

        /** A word this close to either edge of the snippet is stuck there, not placed there. */
        private const val SATURATION_MARGIN_US = 150_000L

        /** How much longer than the rough span a believable refinement may be. */
        private const val IMPLAUSIBLE_FACTOR = 2
        private const val IMPLAUSIBLE_SLACK_US = 1_000_000L
    }
}
