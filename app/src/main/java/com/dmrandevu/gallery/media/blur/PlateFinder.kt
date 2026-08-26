package com.dmrandevu.gallery.media.blur

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Finds licence plates with a detector trained to spot them, rather than by trying to read them.
 *
 * The model is `morsetechlab/yolov11-license-plate-detection` (nano, single class, 640x640) —
 * the same one the Trafy camera app runs. It is **AGPL-3.0**, which travels with anything it is
 * shipped in.
 *
 * This replaces reading the plate with the text recogniser, which could only find one it could
 * also read: on a 356x638 clip whose plates are about 45 px wide, that meant a couple of readings
 * across the whole video, because the characters were well under the size the recogniser needs.
 * A detector has no such floor — a plate does not have to be legible to be recognisably a plate.
 */
class PlateFinder(
    context: Context,
    /**
     * The square the frame is fitted into before the model sees it. The export carries dynamic
     * axes, so this is a real dial: smaller is quicker and finds fewer of the smaller plates.
     */
    private val inputSize: Int = DEFAULT_INPUT_SIZE
) : RegionFinder {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(MODEL_ASSET).use { input ->
        environment.createSession(input.readBytes(), OrtSession.SessionOptions())
    }
    private val inputName: String = session.inputNames.first()

    /** Reused between frames: a 640-square image is 4.9 MB and there is one per sample. */
    private val input = FloatBuffer.allocate(3 * inputSize * inputSize)
    private val pixels = IntArray(inputSize * inputSize)

    override val samplePeriodMs = BlurTimeline.SAMPLE_PERIOD_MS

    override suspend fun regionsIn(frame: ScannedFrame): List<BlurTimeline.Region> {
        val picture = frame.colour()
        val letterbox = Letterbox(picture.width, picture.height, inputSize)
        fill(picture, letterbox)

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val boxes = OnnxTensor.createTensor(environment, input, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                decode(result[0].value as Array<Array<FloatArray>>)
            }
        }

        return suppressOverlaps(boxes).map {
            BlurTimeline.Region(letterbox.toFrame(it), BlurTimeline.Shape.RECTANGLE)
        }
    }

    /** Scales the frame into the square the model wants, padding rather than stretching. */
    private class Letterbox(sourceWidth: Int, sourceHeight: Int, size: Int) {
        val scale = min(size.toFloat() / sourceWidth, size.toFloat() / sourceHeight)
        val width = (sourceWidth * scale).toInt()
        val height = (sourceHeight * scale).toInt()
        val left = (size - width) / 2
        val top = (size - height) / 2

        /** A box in model pixels, back to a fraction of the original frame. */
        fun toFrame(box: RectF) = RectF(
            ((box.left - left) / width).coerceIn(0f, 1f),
            ((box.top - top) / height).coerceIn(0f, 1f),
            ((box.right - left) / width).coerceIn(0f, 1f),
            ((box.bottom - top) / height).coerceIn(0f, 1f)
        )
    }

    private fun fill(picture: Bitmap, letterbox: Letterbox) {
        val scaled = Bitmap.createScaledBitmap(picture, letterbox.width, letterbox.height, true)
        pixels.fill(PADDING)
        scaled.getPixels(
            pixels,
            letterbox.top * inputSize + letterbox.left,
            inputSize,
            0,
            0,
            letterbox.width,
            letterbox.height
        )

        // Channels first, each 0..1, which is what an Ultralytics export expects.
        input.rewind()
        val plane = inputSize * inputSize
        for (i in 0 until plane) {
            val pixel = pixels[i]
            input.put(i, ((pixel shr 16) and 0xFF) / 255f)
            input.put(plane + i, ((pixel shr 8) and 0xFF) / 255f)
            input.put(2 * plane + i, (pixel and 0xFF) / 255f)
        }
        input.rewind()
    }

    /**
     * Reads the `[1, 5, anchors]` block the model produces: four box numbers and one score per
     * anchor, the box given as centre and size in model pixels.
     */
    private fun decode(output: Array<Array<FloatArray>>): List<RectF> {
        val channels = output[0]
        val anchors = channels[0].size
        val found = mutableListOf<RectF>()
        for (i in 0 until anchors) {
            if (channels[4][i] < CONFIDENCE) continue
            val centreX = channels[0][i]
            val centreY = channels[1][i]
            val halfWidth = channels[2][i] / 2
            val halfHeight = channels[3][i] / 2
            found += RectF(
                centreX - halfWidth,
                centreY - halfHeight,
                centreX + halfWidth,
                centreY + halfHeight
            )
        }
        return found
    }

    /** One plate wins one box: anchors near it all fire, and only the strongest is kept. */
    private fun suppressOverlaps(boxes: List<RectF>): List<RectF> {
        val kept = mutableListOf<RectF>()
        for (box in boxes.sortedByDescending { it.width() * it.height() }) {
            if (kept.none { overlap(it, box) > MAX_OVERLAP }) kept += box
            if (kept.size == BlurTimeline.MAX_REGIONS) break
        }
        return kept
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (width <= 0 || height <= 0) return 0f
        val intersection = width * height
        return intersection /
            (a.width() * a.height() + b.width() * b.height() - intersection)
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_ASSET = "models/plate-detector.onnx"

        /** The size to run at, given whether the quicker setting is wanted. */
        fun inputSizeFor(fast: Boolean) = if (fast) FAST_INPUT_SIZE else DEFAULT_INPUT_SIZE

        /**
         * What the model was exported at, and where it finds the most.
         *
         * NNAPI and XNNPACK were both tried here and neither moved the numbers at all — same
         * timings to within noise, same detections — so the session is left on plain CPU rather
         * than carrying options that do nothing.
         */
        const val DEFAULT_INPUT_SIZE = 640

        /**
         * The quicker setting. On the reference clip it scanned in 47 s against 78 s, and covered
         * 120 sampled moments against 179 — so about 40% off the wait for a third of the plates.
         */
        const val FAST_INPUT_SIZE = 416

        /**
         * Below the usual quarter, because the model was trained on international plates and is
         * less sure of Turkish ones — the value the Trafy camera app settled on for the same
         * model and the same footage.
         */
        const val CONFIDENCE = 0.15f
        const val MAX_OVERLAP = 0.45f

        /** Ultralytics pads its letterbox with mid grey; the model has only ever seen that. */
        const val PADDING = 0xFF747474.toInt()
    }
}
