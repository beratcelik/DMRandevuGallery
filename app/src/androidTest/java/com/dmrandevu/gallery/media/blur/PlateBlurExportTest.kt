package com.dmrandevu.gallery.media.blur

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dmrandevu.gallery.media.ExportOptions
import com.dmrandevu.gallery.media.VideoExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Checks that the plate pass flattened the plates it found.
 *
 * Deliberately *not* "the detector no longer finds a plate in the export" — it still does, and
 * should. A mosaicked plate is still plate-shaped and still sitting where plates sit; what has
 * gone is the number. So this asks a question the export can answer on its own: inside each box
 * the detector points at, has the picture gone flat? A mosaic cell is a single colour, so the
 * fine detail that plate characters are made of cannot survive one.
 *
 * Everything is measured inside the exported file, which sidesteps the fact that asking two
 * differently-encoded files for "the frame at t" does not reliably return the same moment.
 *
 * Needs a clip with a visible plate at the shared sample path; skips without one.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlateBlurExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample = File(context.getExternalFilesDir(null), "faceblur_test.mp4")

    @Test
    fun blurredExportFlattensThePlatesItFound() = runBlocking {
        assumeTrue("No sample video at ${sample.path}", sample.exists())

        val input = File(context.cacheDir, "plate_input.mp4").also { sample.copyTo(it, true) }
        val output = File(context.cacheDir, "plate_output.mp4").also { it.delete() }

        val result = VideoExporter(context, noCensor(context))
            .export(input, output, ExportOptions(blurPlates = true)) {}
        assumeTrue(
            "No plates found in the sample — use a clip with a visible plate",
            result is VideoExporter.Result.Exported
        )
        val covered = (result as VideoExporter.Result.Exported).blurred!!

        val before = plateDetail(input, covered)
        val after = plateDetail(output, covered)
        assumeTrue("No frame with exactly one plate to measure", before.isNotEmpty())

        val was = before.sorted()[before.size / 2]
        val now = after.sorted()[after.size / 2]
        assertTrue(
            "Plate regions kept their detail ($was -> $now across ${after.size} frames) — the " +
                "mosaic is missing them",
            now < was * MAX_DETAIL_KEPT
        )
    }

    /** Detail inside each plate box the export covered, frame by frame. */
    private fun plateDetail(file: File, covered: BlurTimeline): List<Double> {
        val retriever = MediaMetadataRetriever()
        val measured = mutableListOf<Double>()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val boxes = FloatArray(BlurTimeline.MAX_REGIONS * 4)
            for (ms in 0 until durationMs step SAMPLE_MS) {
                if (covered.boxesAt(ms * 1_000, boxes) != 1) continue
                val frame = retriever.getFrameAtTime(
                    ms * 1_000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                measured += detail(frame, boxes[0], boxes[1], boxes[2] * CORE, boxes[3] * CORE)
            }
        } finally {
            retriever.release()
        }
        return measured
    }

    /** Mean brightness step between neighbouring pixels in a normalized box. */
    private fun detail(bitmap: Bitmap, cx: Float, cy: Float, halfW: Float, halfH: Float): Double {
        val left = ((cx - halfW) * bitmap.width).toInt().coerceIn(0, bitmap.width - 2)
        val right = ((cx + halfW) * bitmap.width).toInt().coerceIn(left + 2, bitmap.width)
        val top = ((cy - halfH) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = ((cy + halfH) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)

        var total = 0.0
        var count = 0
        for (y in top until bottom) {
            for (x in left until right - 1) {
                total += abs(luma(bitmap.getPixel(x, y)) - luma(bitmap.getPixel(x + 1, y)))
                count++
            }
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun luma(pixel: Int) =
        0.299 * (pixel shr 16 and 0xFF) + 0.587 * (pixel shr 8 and 0xFF) + 0.114 * (pixel and 0xFF)

    private companion object {
        const val SAMPLE_MS = 200L

        /** Well inside the mosaic, away from its softened rim. */
        const val CORE = 0.5f

        /**
         * How much of the plate's own detail may survive. Not zero: a small plate gets only a few
         * mosaic cells across it, and their edges are themselves detail, as is what the encoder
         * puts back. What must go is the black-on-white contrast of the characters.
         */
        const val MAX_DETAIL_KEPT = 0.7
    }

    /** These tests exercise the picture, not the audio; the censor is never switched on. */
    private fun noCensor(context: android.content.Context) =
        com.dmrandevu.gallery.media.censor.AudioCensor(
            context,
            com.dmrandevu.gallery.media.censor.CensorModels(context, okhttp3.OkHttpClient())
        )
}
