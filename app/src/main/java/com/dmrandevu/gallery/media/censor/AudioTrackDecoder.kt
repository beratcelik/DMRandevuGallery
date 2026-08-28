package com.dmrandevu.gallery.media.censor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Decodes a video's audio track to PCM, all of it, in one linear pass.
 *
 * The whole track is held in memory on purpose. Recognition needs to see the audio end to end
 * before it can say where the swearing is, and the censoring pass then has to reach back into
 * the middle of it — so there is nothing to stream. A minute of 48 kHz stereo is about 11 MB,
 * and these clips are Instagram DMs rather than films.
 */
class AudioTrackDecoder {

    /**
     * Interleaved 16-bit samples, exactly as the decoder produced them.
     *
     * [frameCount] counts frames, not samples: one frame is one sample per channel, which is the
     * unit everything downstream indexes by.
     */
    class DecodedAudio(
        val samples: ShortArray,
        val sampleRate: Int,
        val channelCount: Int
    ) {
        val frameCount: Long get() = samples.size.toLong() / channelCount
        val durationUs: Long get() = frameCount * 1_000_000L / sampleRate
    }

    class UnsupportedAudioException(message: String) : Exception(message)

    /**
     * Decodes the audio of [input], or returns null when it has no audio track — a silent video
     * has nothing to censor and must not be treated as a failure.
     */
    suspend fun decode(input: File, onProgress: (Float) -> Unit): DecodedAudio? =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return@withContext null

                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                decodeTrack(extractor, format, onProgress)
            } finally {
                extractor.release()
            }
        }

    private suspend fun decodeTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        onProgress: (Float) -> Unit
    ): DecodedAudio {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        val codec = MediaCodec.createDecoderByType(mime)
        val collected = ShortArrayBuilder()

        // What the decoder actually produced, which is not necessarily what the container
        // declared: it can change these on the format-changed callback, and a resampled or
        // downmixed decode would leave every frame index wrong.
        var sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        var channelCount = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0)

        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = output.intOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        val encoding = output.intOr(
                            MediaFormat.KEY_PCM_ENCODING, AUDIO_FORMAT_PCM_16BIT
                        )
                        // Everything downstream indexes 16-bit frames. A float or 8-bit decode
                        // would be read as noise and beeped in the wrong places, so stop instead.
                        if (encoding != AUDIO_FORMAT_PCM_16BIT) {
                            throw UnsupportedAudioException(
                                "Decoder produced PCM encoding $encoding, expected 16-bit"
                            )
                        }
                    }

                    else -> {
                        if (index < 0) continue
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts: ShortBuffer =
                                buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            collected.append(shorts)
                            if (durationUs > 0) {
                                onProgress(
                                    (info.presentationTimeUs.toFloat() / durationUs)
                                        .coerceIn(0f, 1f)
                                )
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        if (sampleRate <= 0 || channelCount <= 0) {
            throw UnsupportedAudioException(
                "Audio track declared $channelCount channels at $sampleRate Hz"
            )
        }
        onProgress(1f)
        return DecodedAudio(collected.toShortArray(channelCount), sampleRate, channelCount)
    }

    /** Grows geometrically so a long track does not become a chain of array copies. */
    private class ShortArrayBuilder {
        private var data = ShortArray(INITIAL_CAPACITY)
        private var size = 0

        fun append(source: ShortBuffer) {
            val count = source.remaining()
            if (size + count > data.size) {
                var capacity = data.size
                while (capacity < size + count) capacity *= 2
                data = data.copyOf(capacity)
            }
            source.get(data, size, count)
            size += count
        }

        /** Trimmed to whole frames: a truncated final frame would shift every channel after it. */
        fun toShortArray(channelCount: Int): ShortArray =
            data.copyOf(size - size % channelCount)

        private companion object {
            const val INITIAL_CAPACITY = 1 shl 20
        }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /** [android.media.AudioFormat.ENCODING_PCM_16BIT], without the import for one constant. */
        const val AUDIO_FORMAT_PCM_16BIT = 2
    }
}

private fun MediaFormat.intOr(key: String, fallback: Int) =
    if (containsKey(key)) getInteger(key) else fallback
