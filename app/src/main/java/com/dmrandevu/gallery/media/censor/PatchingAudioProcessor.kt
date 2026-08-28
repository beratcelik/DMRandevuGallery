package com.dmrandevu.gallery.media.censor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Substitutes the censored stretches into the audio on its way through the export.
 *
 * Everything outside a patch is copied through byte for byte. The alternative — handing the
 * encoder a whole freshly rendered track — would put every second of the video through
 * separation and resampling to change the half-second that needed it.
 *
 * Position is counted in frames from the start of the stream. media3 hands the processors the
 * decoder's own output with nothing trimmed ahead of it, so frame zero here is frame zero of the
 * file, which is what the plan's offsets were measured against.
 */
@UnstableApi
class PatchingAudioProcessor(private val plan: CensorPlan) : BaseAudioProcessor() {

    class MisalignedAudioException(message: String) : Exception(message)

    private var bytesPerFrame = 0
    private var framesSeen = 0L

    /** Set aside so it can be raised from [queueEndOfStream], where throwing is allowed. */
    private var misalignment: String? = null

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        // Checked rather than assumed. Every offset in the plan is a frame number at a particular
        // rate and channel count; if the decoder hands over something else, those numbers point
        // at the wrong moments and the beeps land over innocent words while the swearing plays.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.sampleRate != plan.sampleRate ||
            inputAudioFormat.channelCount != plan.channelCount
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        return inputAudioFormat
    }

    override fun isActive(): Boolean = !plan.isEmpty

    override fun queueInput(inputBuffer: ByteBuffer) {
        val available = inputBuffer.remaining()
        if (available == 0) return

        val output = replaceOutputBuffer(available)
        val startFrame = framesSeen
        val frames = available / bytesPerFrame

        // Straight copy first; the patches then overwrite the parts they cover.
        val position = inputBuffer.position()
        output.put(inputBuffer)
        inputBuffer.position(position + available)
        output.flip()

        val endFrame = startFrame + frames
        for (patch in plan.patches) {
            if (patch.endFrame <= startFrame || patch.startFrame >= endFrame) continue
            overwrite(output, patch, startFrame, frames)
        }

        framesSeen = endFrame
    }

    /** Writes the overlapping part of [patch] into [output], which holds [frames] from [start]. */
    private fun overwrite(
        output: ByteBuffer,
        patch: BeepPatcher.PcmPatch,
        start: Long,
        frames: Int
    ) {
        val from = maxOf(patch.startFrame, start)
        val to = minOf(patch.endFrame, start + frames)
        val channels = plan.channelCount
        val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()

        for (frame in from until to) {
            val intoBuffer = ((frame - start) * channels).toInt()
            val intoPatch = ((frame - patch.startFrame) * channels).toInt()
            for (channel in 0 until channels) {
                shorts.put(intoBuffer + channel, patch.samples[intoPatch + channel])
            }
        }
    }

    override fun onQueueEndOfStream() {
        // The plan's frame numbers came from a separate decode of the same file. If this decode
        // disagreed about the length, they were pointing at the wrong moments all along — so the
        // export fails rather than shipping a video whose beeps are in the wrong places.
        val drift = abs(framesSeen - plan.sourceFrameCount)
        val tolerance = plan.sampleRate.toLong() * TOLERANCE_MS / 1000
        if (drift > tolerance) {
            misalignment =
                "Audio was $framesSeen frames, the plan expected ${plan.sourceFrameCount}"
        }
    }

    /** Raised after the stream ends; see [onQueueEndOfStream]. */
    fun failureOrNull(): MisalignedAudioException? =
        misalignment?.let(::MisalignedAudioException)

    override fun onFlush() {
        framesSeen = 0
        misalignment = null
    }

    private companion object {
        /**
         * Encoder priming can shift the two decodes by a frame or two; anything past this is a
         * real disagreement rather than a rounding difference.
         */
        const val TOLERANCE_MS = 250L
    }
}
