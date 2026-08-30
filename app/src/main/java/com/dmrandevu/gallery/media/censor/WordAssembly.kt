package com.dmrandevu.gallery.media.censor

import com.dmrandevu.whisper.WhisperContext

/**
 * Turns what the recognizer returns into timed words.
 *
 * Its own object rather than a private method because the timing of these words is the thing
 * that decides where a beep lands, and a test that reimplements the assembly to check it proves
 * nothing about what actually runs — which is exactly how a 650 ms error survived a passing test.
 */
object WordAssembly {

    fun fromTokens(segments: List<WhisperContext.Segment>): List<TimedWord> {
        // Built from tokens rather than segments. Alignment places every token it can, but it
        // stops whisper splitting a segment per token — so a segment is a whole phrase, and
        // taking its start as a word's start put the beep 650 ms early on the operator's clip.
        val tokens = segments.flatMap { it.tokens }.filter { it.text.isNotBlank() }
        if (tokens.none { it.alignedMs != null }) return fromSegments(segments)

        // Alignment does not place every token, and a token it skipped still carries text. An
        // earlier version dropped those, which merged whole phrases into single "words" — 45
        // words became 7 — and left the timings attached to the wrong syllables.
        val times = LongArray(tokens.size) { -1L }
        tokens.forEachIndexed { index, token -> token.alignedMs?.let { times[index] = it } }
        var next = -1L
        for (index in tokens.indices.reversed()) {
            if (times[index] >= 0) next = times[index] else times[index] = next
        }
        var previous = times.first { it >= 0 }
        for (index in tokens.indices) {
            if (times[index] < 0) times[index] = previous else previous = times[index]
        }

        val words = ArrayList<TimedWord>()
        var text = StringBuilder()
        var startMs = -1L

        fun flush(endMs: Long) {
            val finished = text.toString().trim()
            if (finished.isNotEmpty() && startMs >= 0) {
                words.add(
                    TimedWord(
                        finished,
                        startMs * 1_000,
                        maxOf(endMs, startMs + MIN_WORD_MS) * 1_000
                    )
                )
            }
            text = StringBuilder()
            startMs = -1L
        }

        for ((index, token) in tokens.withIndex()) {
            // A leading space is how whisper marks the start of a word; the rest are its middle.
            if (token.text.startsWith(" ") || startMs < 0) {
                flush(times[index])
                startMs = times[index]
            }
            text.append(token.text.trim())
        }
        flush(times.last() + MIN_WORD_MS)
        return words
    }

    /** Fallback for a model loaded without alignment; the timings are the coarse ones. */
    private fun fromSegments(segments: List<WhisperContext.Segment>): List<TimedWord> {
        val words = ArrayList<TimedWord>()
        var text = StringBuilder()
        var startMs = 0L
        var endMs = 0L

        fun flush() {
            val finished = text.toString().trim()
            if (finished.isNotEmpty()) {
                words.add(
                    TimedWord(finished, startMs * 1_000, maxOf(endMs, startMs + MIN_WORD_MS) * 1_000)
                )
            }
            text = StringBuilder()
        }

        for (segment in segments) {
            if (segment.text.isBlank()) continue
            if (segment.text.startsWith(" ") || text.isEmpty()) {
                flush()
                startMs = segment.startMs
            }
            text.append(segment.text.trim())
            endMs = segment.endMs
        }
        flush()
        return words
    }

    /** Floor on a word's length, so alignment placing two tokens together still beeps. */
    const val MIN_WORD_MS = 120L
}
