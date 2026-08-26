package com.dmrandevu.gallery.media.blur

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import com.google.mlkit.vision.common.InputImage

/**
 * One decoded frame, offered in whichever form a [RegionFinder] needs.
 *
 * Only valid until the decoder takes its buffer back, so a finder must be done with it before it
 * returns.
 */
class ScannedFrame(private val image: Image, private val rotation: Int) {

    /** The decoder's own image, wrapped. No copy, so this is the cheap one. */
    fun original(): InputImage = InputImage.fromMediaImage(image, rotation)

    /**
     * The frame in colour, upright, for a detector that was trained on colour photographs.
     *
     * Built at most once per frame however many finders ask for it, because the conversion walks
     * every pixel and a scan does this a few hundred times per video.
     */
    fun colour(): Bitmap = colour ?: buildColour().also { colour = it }

    private var colour: Bitmap? = null

    /** YUV 4:2:0 to RGB, then turned upright if the container says the video is rotated. */
    private fun buildColour(): Bitmap {
        val width = image.width
        val height = image.height
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val pixels = IntArray(width * height)

        var out = 0
        for (row in 0 until height) {
            val yRow = row * y.rowStride
            val uRow = (row / 2) * u.rowStride
            val vRow = (row / 2) * v.rowStride
            for (column in 0 until width) {
                val luma = (yBuffer.get(yRow + column * y.pixelStride).toInt() and 0xFF) - 16
                val chroma = column / 2
                val cb = (uBuffer.get(uRow + chroma * u.pixelStride).toInt() and 0xFF) - 128
                val cr = (vBuffer.get(vRow + chroma * v.pixelStride).toInt() and 0xFF) - 128
                // BT.601 in fixed point; the shift is the divide by 1024 the constants imply.
                val scaled = 1192 * luma
                val red = (scaled + 1634 * cr) shr 10
                val green = (scaled - 833 * cr - 400 * cb) shr 10
                val blue = (scaled + 2066 * cb) shr 10
                pixels[out++] = OPAQUE or
                    (red.coerceIn(0, 255) shl 16) or
                    (green.coerceIn(0, 255) shl 8) or
                    blue.coerceIn(0, 255)
            }
        }

        val decoded = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (rotation % 360 == 0) return decoded
        val turn = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, width, height, turn, true)
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24
    }
}
