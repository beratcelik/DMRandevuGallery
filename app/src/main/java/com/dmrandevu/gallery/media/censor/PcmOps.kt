package com.dmrandevu.gallery.media.censor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sample arithmetic the censor pass needs: rate and tempo changes on the way into
 * recognition, and the beep and crossfades on the way out.
 *
 * Rate conversion goes through media3's own Sonic rather than anything written here. It already
 * ships with the app, and a naive resampler folds everything above the new Nyquist back down as
 * aliasing — on speech that is a hiss laid over the very words being listened for.
 */
@UnstableApi
object PcmOps {

    /** What the recognizer expects: one channel at 16 kHz. */
    const val ASR_SAMPLE_RATE = 16_000

    /** What the separation model was trained on. */
    const val SEPARATION_SAMPLE_RATE = 44_100

    /**
     * Averages every channel into one.
     *
     * Averaged rather than left-channel-only: speech panned to one side would otherwise arrive
     * at the recognizer quiet or missing.
     */
    fun downmixToMono(samples: ShortArray, channelCount: Int): ShortArray {
        if (channelCount == 1) return samples
        val frames = samples.size / channelCount
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            val base = frame * channelCount
            for (channel in 0 until channelCount) sum += samples[base + channel]
            mono[frame] = (sum / channelCount).toShort()
        }
        return mono
    }

    /** Splits interleaved samples into one array per channel, as the separation model wants. */
    fun deinterleave(samples: ShortArray, channelCount: Int): Array<FloatArray> {
        val frames = samples.size / channelCount
        return Array(channelCount) { channel ->
            FloatArray(frames) { frame -> samples[frame * channelCount + channel] / 32768f }
        }
    }

    fun interleave(channels: Array<FloatArray>): ShortArray {
        val channelCount = channels.size
        val frames = channels[0].size
        val out = ShortArray(frames * channelCount)
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                out[frame * channelCount + channel] = clampToShort(channels[channel][frame] * 32768f)
            }
        }
        return out
    }

    fun toFloat(samples: ShortArray) = FloatArray(samples.size) { samples[it] / 32768f }

    /** Resamples without touching the playback speed. */
    fun resample(
        samples: ShortArray,
        channelCount: Int,
        fromRate: Int,
        toRate: Int
    ): ShortArray {
        if (fromRate == toRate) return samples
        val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(toRate) }
        return runOffline(sonic, samples, fromRate, channelCount)
    }

    /**
     * Stretches or compresses time while leaving pitch alone.
     *
     * Slowing the audio down is what makes the recognizer hear swearing it otherwise replaces
     * with an innocent near-homophone; pitch has to stay put, or the voice stops sounding like a
     * voice and the recognition gets worse instead of better.
     */
    fun changeTempo(
        samples: ShortArray,
        channelCount: Int,
        sampleRate: Int,
        speed: Float
    ): ShortArray {
        if (speed == 1f) return samples
        val sonic = SonicAudioProcessor().apply { setSpeed(speed) }
        return runOffline(sonic, samples, sampleRate, channelCount)
    }

    /** Mono, 16 kHz, floats — one call, because every recognizer pass starts this way. */
    fun forRecognition(
        samples: ShortArray,
        channelCount: Int,
        sampleRate: Int,
        speed: Float = 1f
    ): FloatArray {
        val mono = downmixToMono(samples, channelCount)
        val slowed = changeTempo(mono, 1, sampleRate, speed)
        return toFloat(resample(slowed, 1, sampleRate, ASR_SAMPLE_RATE))
    }

    /**
     * Drives an [AudioProcessor] to completion off the playback thread.
     *
     * Sonic is written to be fed a bit at a time by a player. Here there is no player, so the
     * whole array is pushed through and drained by hand.
     */
    private fun runOffline(
        processor: AudioProcessor,
        samples: ShortArray,
        sampleRate: Int,
        channelCount: Int
    ): ShortArray {
        val format = AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val input = ByteBuffer
            .allocateDirect(samples.size * 2)
            .order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(samples)
        input.limit(samples.size * 2)

        val collected = ArrayList<ShortArray>()
        var total = 0

        fun drain() {
            while (true) {
                val output = processor.getOutput()
                if (!output.hasRemaining()) break
                val shorts = ShortArray(output.remaining() / 2)
                output.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                // getOutput() hands back its own buffer and expects it consumed in full.
                output.position(output.limit())
                collected.add(shorts)
                total += shorts.size
            }
        }

        while (input.hasRemaining()) {
            val before = input.remaining()
            processor.queueInput(input)
            drain()
            // Sonic always takes something; bailing out rather than spinning forever if it ever
            // stops, since a hung export is worse than a failed one.
            if (input.remaining() == before) break
        }
        processor.queueEndOfStream()
        while (!processor.isEnded) {
            val before = total
            drain()
            if (total == before) break
        }
        processor.reset()

        val out = ShortArray(total)
        var offset = 0
        for (chunk in collected) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /**
     * A tone that covers the word without startling anyone: a sine at [BEEP_HZ], eased in and out
     * so it starts and stops without a click.
     *
     * Written over [destination] in place, one channel's worth at a time, added to whatever is
     * already there — which is the separated background, so the music carries on underneath.
     */
    fun mixBeepInto(
        destination: Array<FloatArray>,
        startFrame: Int,
        endFrame: Int,
        sampleRate: Int,
        level: Float = BEEP_LEVEL
    ) {
        val span = endFrame - startFrame
        if (span <= 0) return
        val fade = (sampleRate * BEEP_FADE_MS / 1000).coerceAtMost(span / 2).coerceAtLeast(1)
        for (i in 0 until span) {
            val envelope = when {
                i < fade -> raisedCosine(i.toFloat() / fade)
                i > span - fade -> raisedCosine((span - i).toFloat() / fade)
                else -> 1f
            }
            val value = level * envelope * sin(2.0 * PI * BEEP_HZ * i / sampleRate).toFloat()
            for (channel in destination) {
                val index = startFrame + i
                if (index in channel.indices) channel[index] += value
            }
        }
    }

    /**
     * Eases [patched] into [original] across [frames] at each end.
     *
     * The patched audio and the untouched audio around it do not meet at the same point in the
     * waveform, and a hard join between them is an audible click on every beep.
     */
    fun crossfadeEdges(
        original: Array<FloatArray>,
        patched: Array<FloatArray>,
        frames: Int
    ) {
        val length = patched[0].size
        val fade = frames.coerceAtMost(length / 2)
        if (fade <= 0) return
        for (channel in patched.indices) {
            for (i in 0 until fade) {
                val a = i.toFloat() / fade
                patched[channel][i] =
                    original[channel][i] * (1 - a) + patched[channel][i] * a
                val end = length - 1 - i
                patched[channel][end] =
                    original[channel][end] * (1 - a) + patched[channel][end] * a
            }
        }
    }

    private fun raisedCosine(t: Float) = (0.5 - 0.5 * cos(PI * t)).toFloat()

    private fun clampToShort(value: Float): Short =
        value.coerceIn(-32768f, 32767f).toInt().toShort()

    /** Clear of speech, and the pitch every television has trained people to read as censoring. */
    const val BEEP_HZ = 1000.0

    /** −12 dBFS: over the top of the background without clipping when it is already loud. */
    const val BEEP_LEVEL = 0.25f

    private const val BEEP_FADE_MS = 5

    /** How long the patched audio takes to blend into the untouched audio at each edge. */
    const val CROSSFADE_MS = 15
}
