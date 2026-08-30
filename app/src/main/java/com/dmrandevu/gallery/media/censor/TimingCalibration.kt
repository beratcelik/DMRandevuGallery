package com.dmrandevu.gallery.media.censor

import androidx.media3.common.util.UnstableApi
import kotlin.math.sqrt

/**
 * Works out how far the recognizer's word timings run ahead of the audio, for this clip.
 *
 * They do run ahead — measured on the operator's own video, "Amına koydu mu" is reported at
 * 30.00-30.97 s and is really at 30.68-31.78 s. The size of that error is not a constant worth
 * hard-coding, though: it depends on the clip and on what the recognizer made of it. Guessing
 * high means beeping a second of innocent audio before every swear word, which the operator
 * noticed immediately.
 *
 * So it is measured instead. The recognizer says which stretches are speech; the audio says where
 * the sound actually is. Sliding one against the other and taking the best fit gives the offset
 * directly, with no assumption about its size.
 */
@UnstableApi
object TimingCalibration {

    /**
     * [shiftUs] is how much later the audio is than the recognizer claims — add it to a word's
     * times to put them over the sound. [confident] is false when the clip gave no clear answer,
     * and the caller should widen its windows rather than trust a number.
     */
    data class Result(val shiftUs: Long, val confident: Boolean) {
        companion object {
            val UNKNOWN = Result(0, confident = false)
        }
    }

    /**
     * Estimates the offset for [words] against [audio].
     *
     * Only positive offsets are searched: every measurement so far has the recognizer early, and
     * allowing negative ones lets a noisy clip pull the beep backwards off the word.
     */
    fun estimate(
        audio: AudioTrackDecoder.DecodedAudio,
        words: List<TimedWord>,
        maxShiftUs: Long = MAX_SHIFT_US
    ): Result {
        if (words.isEmpty() || audio.frameCount == 0L) return Result.UNKNOWN

        val envelope = envelope(audio) ?: return Result.UNKNOWN
        val speech = occupancy(words, envelope.size)
        // A clip needs real gaps between the words to be measurable at all. The recognizer
        // reports its words back to back — each ending exactly where the next begins — so on a
        // talkative clip the speech pattern is one unbroken block, sliding it produces a nearly
        // flat curve, and the peak is noise. Measured on the operator's own video: 80% speech,
        // and the answer came back a confident 240 ms when the truth was nearer 700 ms.
        //
        // Being wrong here is worse than not knowing: too small an offset shortens the window
        // and leaves the end of the swearing audible.
        val talking = speech.count { it > 0f }
        if (talking < MIN_FRAMES) return Result.UNKNOWN
        if (talking > envelope.size * MAX_SPEECH_SHARE) return Result.UNKNOWN

        val steps = (maxShiftUs / FRAME_US).toInt()
        var bestLag = 0
        var best = Float.NEGATIVE_INFINITY
        var total = 0f
        for (lag in 0..steps) {
            val score = fit(speech, envelope, lag)
            total += score
            if (score > best) {
                best = score
                bestLag = lag
            }
        }

        val mean = total / (steps + 1)
        // Two ways to be unsure: the peak barely stands out from the average, or it is jammed
        // against the end of the search, which means the real answer is somewhere beyond it.
        //
        // Deliberately not "better than no shift at all": zero is one of the lags searched, so a
        // clip whose timings already fit peaks there, and requiring an improvement on it threw
        // away the one answer that needs no correction.
        val standsOut = best > mean * PEAK_MARGIN
        val inRange = bestLag < steps
        return Result(bestLag * FRAME_US, confident = standsOut && inRange)
    }

    /** How well the speech pattern lines up with the sound when slid [lag] frames later. */
    private fun fit(speech: FloatArray, envelope: FloatArray, lag: Int): Float {
        var hit = 0f
        var miss = 0f
        var counted = 0
        for (i in speech.indices) {
            val at = i + lag
            if (at >= envelope.size) break
            // Loud where the recognizer says a word is counts for it; loud where it says there is
            // nothing counts against. Without the second term the best fit is always the lag that
            // parks the words over the loudest part of the clip.
            if (speech[i] > 0f) hit += envelope[at] else miss += envelope[at]
            counted++
        }
        // Per frame compared, not per clip. A larger lag slides the end of the words past the end
        // of the audio and compares fewer frames; unnormalised, dropping those frames flatters
        // the longest lags and the answer creeps upwards on any clip that ends quietly.
        if (counted == 0) return Float.NEGATIVE_INFINITY
        return (hit - miss * QUIET_WEIGHT) / counted
    }

    /** Loudness per frame, scaled so the loud parts sit near one. */
    private fun envelope(audio: AudioTrackDecoder.DecodedAudio): FloatArray? {
        val perFrame = (audio.sampleRate * FRAME_US / 1_000_000L).toInt()
        if (perFrame <= 0) return null
        val frames = (audio.frameCount / perFrame).toInt()
        if (frames < MIN_FRAMES * 2) return null

        val channels = audio.channelCount
        val envelope = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0.0
            var count = 0
            var index = frame * perFrame * channels
            repeat(perFrame) {
                if (index < audio.samples.size) {
                    val value = audio.samples[index] / 32768.0
                    sum += value * value
                    count++
                }
                index += channels
            }
            envelope[frame] = if (count > 0) sqrt(sum / count).toFloat() else 0f
        }

        // Scaled against a high percentile rather than the maximum: one bang should not flatten
        // everything else to nothing.
        val loud = envelope.sorted()[(frames * 0.95).toInt().coerceAtMost(frames - 1)]
        if (loud <= 1e-6f) return null
        for (i in envelope.indices) envelope[i] = (envelope[i] / loud).coerceAtMost(1f)
        return envelope
    }

    /** One per frame: above zero where the recognizer places a word. */
    private fun occupancy(words: List<TimedWord>, frames: Int): FloatArray {
        val speech = FloatArray(frames)
        for (word in words) {
            val from = (word.startUs / FRAME_US).toInt().coerceIn(0, frames - 1)
            val to = (word.endUs / FRAME_US).toInt().coerceIn(0, frames - 1)
            for (i in from..to) speech[i] = 1f
        }
        return speech
    }

    /** Frame size for both signals; fine enough to place a beep, coarse enough to stay cheap. */
    const val FRAME_US = 20_000L

    /** The recognizer has never been seen more than about a second early. */
    const val MAX_SHIFT_US = 1_200_000L

    private const val MIN_FRAMES = 25

    /** Past this much speech there are too few gaps left to line anything up against. */
    private const val MAX_SPEECH_SHARE = 0.6f
    private const val PEAK_MARGIN = 1.05f

    /** How much a loud stretch the recognizer called silent counts against a fit. */
    private const val QUIET_WEIGHT = 1.0f
}
