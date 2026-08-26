package com.dmrandevu.gallery.media.blur

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * First half of the blur: walks the video and asks each [RegionFinder] what to cover, roughly
 * every [BlurTimeline.SAMPLE_PERIOD_MS], producing the timeline the shader later reads.
 *
 * Finders share the decode. Faces and plates want the same frames, and decoding the video twice
 * to ask two questions about it would double the wait for nothing.
 *
 * The video is decoded **straight through, once**. The obvious implementation — seek to each
 * sample instant and grab that frame — costs an exact seek per sample, and an exact seek has to
 * decode from the preceding keyframe every time; measured on a 47 s clip that was 39 s of the
 * 65 s pass. Decoding sequentially and simply dropping the frames between samples turns that
 * into one linear decode, which the hardware does in a couple of seconds.
 *
 * Frames reach ML Kit as the decoder's own YUV images, so nothing is ever copied into a Bitmap.
 */
class RegionScanner {

    /**
     * Scans [input] with every [finder] and returns one timeline covering all of them.
     * [onProgress] is called with 0..1.
     */
    suspend fun scan(
        input: File,
        finders: List<RegionFinder>,
        onProgress: (Float) -> Unit
    ): BlurTimeline =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: return@withContext BlurTimeline.empty()

                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val durationUs = format.longOr(MediaFormat.KEY_DURATION, 0L)
                if (durationUs <= 0L) return@withContext BlurTimeline.empty()

                try {
                    decodeAndDetect(extractor, format, durationUs, finders, onProgress)
                } finally {
                    finders.forEach { it.close() }
                }
            } finally {
                extractor.release()
            }
        }

    private suspend fun decodeAndDetect(
        extractor: MediaExtractor,
        format: MediaFormat,
        durationUs: Long,
        finders: List<RegionFinder>,
        onProgress: (Float) -> Unit
    ): BlurTimeline {
        // One builder per finder: chaining a face box onto a plate track because they happened to
        // overlap would drag one region's blur onto the other's path.
        val builders = finders.map { BlurTimeline.Builder(durationUs) }
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        // Rotation is metadata; the decoder ignores it, so ML Kit is told about it instead and
        // hands back boxes already in the upright orientation the shader works in.
        val rotation = format.intOr(MediaFormat.KEY_ROTATION, 0)

        try {
            // No output surface: decoding into buffers is what makes getOutputImage able to hand
            // back CPU-readable YUV. Rendering to an ImageReader instead looks tempting but the
            // decoder is free to answer with an opaque vendor buffer, and reading its planes
            // takes the process down with a native abort.
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            // Decode once at the finest rate anyone asked for, and give each finder its own
            // frames out of that.
            val periodsUs = finders.map { it.samplePeriodMs * 1_000 }
            val nextDueUs = LongArray(finders.size)
            var nextSampleUs = 0L
            val stepUs = periodsUs.min()

            while (true) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer: ByteBuffer = codec.getInputBuffer(index)!!
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
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> {
                        if (index < 0) continue
                        val wanted = info.size > 0 && info.presentationTimeUs >= nextSampleUs
                        if (wanted) {
                            nextSampleUs = info.presentationTimeUs + stepUs
                            // Only valid until the buffer goes back to the decoder, so the
                            // detection has to finish first.
                            codec.getOutputImage(index)?.let { image ->
                                val frame = ScannedFrame(image, rotation)
                                finders.forEachIndexed { i, finder ->
                                    if (info.presentationTimeUs < nextDueUs[i]) return@forEachIndexed
                                    nextDueUs[i] = info.presentationTimeUs + periodsUs[i]
                                    builders[i].addSample(
                                        info.presentationTimeUs,
                                        finder.regionsIn(frame)
                                    )
                                }
                            }
                            onProgress(
                                (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            )
                        }
                        // Frames between samples cost nothing beyond the decode itself.
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return BlurTimeline.of(builders.map { it.build() })
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

private fun MediaFormat.longOr(key: String, fallback: Long) =
    if (containsKey(key)) getLong(key) else fallback

private fun MediaFormat.intOr(key: String, fallback: Int) =
    if (containsKey(key)) getInteger(key) else fallback

