package com.dmrandevu.gallery.media.censor

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Renders the short stretches of audio that will replace the swearing.
 *
 * Only the censor windows are touched, and only they are separated — the expensive part runs on a
 * second or two of audio per swear word rather than the whole clip. Separating a 35 second video
 * end to end took 84 seconds of desktop CPU in the spike, which on a phone would be the whole
 * export; this way it is proportional to how much swearing there is.
 *
 * Everything outside a window is never read, never re-encoded here, and comes through the export
 * untouched.
 */
@UnstableApi
object BeepPatcher {

    /** A stretch of replacement audio, at the source's own rate and channel count. */
    class PcmPatch(val startFrame: Long, val samples: ShortArray, val channelCount: Int) {
        val frameCount: Long get() = samples.size.toLong() / channelCount
        val endFrame: Long get() = startFrame + frameCount
    }

    /**
     * Builds one patch per window in [windows].
     *
     * [onProgress] reports 0..1 across all of them.
     */
    suspend fun render(
        audio: AudioTrackDecoder.DecodedAudio,
        windows: List<CensorWindow>,
        separator: VocalSeparator,
        onProgress: (Float) -> Unit
    ): List<PcmPatch> {
        if (windows.isEmpty()) return emptyList()
        val patches = ArrayList<PcmPatch>(windows.size)
        for ((index, window) in windows.withIndex()) {
            currentCoroutineContext().ensureActive()
            patches.add(render(audio, window, separator))
            onProgress((index + 1f) / windows.size)
        }
        return patches
    }

    private suspend fun render(
        audio: AudioTrackDecoder.DecodedAudio,
        window: CensorWindow,
        separator: VocalSeparator
    ): PcmPatch {
        val rate = audio.sampleRate
        val channels = audio.channelCount

        // The patch covers the window plus a crossfade at each end, so it can be eased into the
        // untouched audio around it rather than butted against it.
        val fadeFrames = rate * PcmOps.CROSSFADE_MS / 1000
        val windowStart = (window.startUs * rate / 1_000_000L)
        val windowEnd = (window.endUs * rate / 1_000_000L)
        val patchStart = (windowStart - fadeFrames).coerceAtLeast(0L)
        val patchEnd = (windowEnd + fadeFrames).coerceAtMost(audio.frameCount)
        val patchFrames = (patchEnd - patchStart).toInt()
        if (patchFrames <= 0) {
            return PcmPatch(patchStart, ShortArray(0), channels)
        }

        // Separation works at its own rate in stereo, so the slice is converted, separated, and
        // converted back. Context on either side is real audio wherever there is any: the model
        // reads a block at a time, and feeding it silence at the edges makes it hear the join.
        val original = slice(audio, patchStart, patchFrames)
        val background = separateBackground(original, rate, channels, separator)

        PcmOps.mixBeepInto(
            destination = background,
            startFrame = (windowStart - patchStart).toInt(),
            endFrame = (windowEnd - patchStart).toInt(),
            sampleRate = rate
        )
        PcmOps.crossfadeEdges(original, background, fadeFrames)

        return PcmPatch(patchStart, PcmOps.interleave(background), channels)
    }

    /**
     * Takes the voice out of [original], leaving the background.
     *
     * The slice is padded out to whole separation blocks with the audio that really surrounds it,
     * and the padding is discarded afterwards.
     */
    private suspend fun separateBackground(
        original: Array<FloatArray>,
        rate: Int,
        channels: Int,
        separator: VocalSeparator
    ): Array<FloatArray> {
        val frames = original[0].size
        val atModelRate = resampleChannels(original, rate, PcmOps.SEPARATION_SAMPLE_RATE)
        val stereo = toStereo(atModelRate)
        val modelFrames = stereo[0].size

        val separated = Array(2) { FloatArray(modelFrames) }
        var offset = 0
        while (offset < modelFrames) {
            currentCoroutineContext().ensureActive()
            // Each block is read from the real signal, centred so the trimmed edges fall outside
            // the part being kept.
            val blockStart = offset - Stft.TRIM
            val block = Array(2) { channel ->
                FloatArray(Stft.CHUNK) { i ->
                    val at = blockStart + i
                    if (at in 0 until modelFrames) stereo[channel][at] else 0f
                }
            }
            val voice = separator.vocalsIn(block)
            val take = minOf(Stft.USABLE, modelFrames - offset)
            for (channel in 0..1) {
                for (i in 0 until take) {
                    val source = Stft.TRIM + i
                    separated[channel][offset + i] =
                        stereo[channel][offset + i] - voice[channel][source]
                }
            }
            offset += Stft.USABLE
        }

        val backAtSourceRate = resampleChannels(
            separated, PcmOps.SEPARATION_SAMPLE_RATE, rate
        )
        // Resampling rarely lands on exactly the frame count it started from; the patch has to be
        // the length the caller expects, so it is trimmed or held at its last value.
        return Array(channels) { channel ->
            val source = backAtSourceRate[channel.coerceAtMost(backAtSourceRate.size - 1)]
            FloatArray(frames) { i -> if (i < source.size) source[i] else 0f }
        }
    }

    private fun slice(
        audio: AudioTrackDecoder.DecodedAudio,
        startFrame: Long,
        frames: Int
    ): Array<FloatArray> {
        val channels = audio.channelCount
        return Array(channels) { channel ->
            FloatArray(frames) { i ->
                val index = ((startFrame + i) * channels + channel).toInt()
                if (index in audio.samples.indices) audio.samples[index] / 32768f else 0f
            }
        }
    }

    private fun toStereo(channels: Array<FloatArray>): Array<FloatArray> = when (channels.size) {
        2 -> channels
        // A mono track is fed to both sides; the model has never seen anything else.
        1 -> arrayOf(channels[0], channels[0].copyOf())
        else -> arrayOf(channels[0], channels[1])
    }

    private fun resampleChannels(
        channels: Array<FloatArray>,
        fromRate: Int,
        toRate: Int
    ): Array<FloatArray> {
        if (fromRate == toRate) return channels
        return Array(channels.size) { channel ->
            val shorts = ShortArray(channels[channel].size) { i ->
                (channels[channel][i] * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
            val resampled = PcmOps.resample(shorts, 1, fromRate, toRate)
            FloatArray(resampled.size) { resampled[it] / 32768f }
        }
    }
}
