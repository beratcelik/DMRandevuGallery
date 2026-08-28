package com.dmrandevu.gallery.media.blur

import android.media.MediaMetadataRetriever
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.dmrandevu.gallery.media.ExportOptions
import com.dmrandevu.gallery.media.VideoExporter
import java.io.File
import kotlin.math.abs

/**
 * Runs the real blur pipeline on a real video, on real hardware — the encoder, the GL shader and
 * the detector all behave differently per device, so this is the only place their wiring is
 * actually proven.
 *
 * The check is the feature's actual promise rather than a proxy for it: point the face detector
 * at the exported video and it should no longer find the faces it found in the original. That
 * also catches the blur landing in the wrong place — a mosaic painted at the vertically mirrored
 * position would leave every face perfectly detectable.
 *
 * No sample video ships with the repo: this feature exists to keep faces out of places they do
 * not belong, and committing one would do the opposite. Push a clip with a visible face into the
 * app's own external files directory (no storage permission needed there) and the test runs;
 * otherwise it skips.
 *
 *   adb push some_clip.mp4 \
 *     /sdcard/Android/data/com.dmrandevu.gallery/files/faceblur_test.mp4
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FaceBlurExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample = File(context.getExternalFilesDir(null), "faceblur_test.mp4")

    @Test
    fun blurredExportNoLongerShowsItsFaces() = runBlocking {
        assumeTrue("No sample video at ${sample.path}", sample.exists())

        val input = File(context.cacheDir, "test_input.mp4").also { sample.copyTo(it, true) }
        val output = File(context.cacheDir, "test_output.mp4").also { it.delete() }

        val result = VideoExporter(context, noCensor(context))
            .export(input, output, ExportOptions(blurFaces = true)) {}
        assumeTrue(
            "No faces detected in the sample — use a clip with a visible face",
            result is VideoExporter.Result.Exported
        )
        assertTrue("Output is empty", output.length() > 0)

        val before = probe(input)
        val after = probe(output)
        // Coded orientation has to survive too, not just the displayed size: a player that
        // ignores rotation metadata would otherwise show the export on its side.
        assertEquals("Width changed", before.width, after.width)
        assertEquals("Height changed", before.height, after.height)
        assertEquals("Rotation changed", before.rotationDegrees, after.rotationDegrees)
        assertTrue(
            "Duration drifted: ${before.durationMs} -> ${after.durationMs}",
            abs(before.durationMs - after.durationMs) < 200
        )
        assertEquals("Audio track lost", before.hasAudio, after.hasAudio)

        // The timeline the export actually applied, versus what is still findable afterwards.
        val covered = (result as VideoExporter.Result.Exported).blurred!!
        val leaked = RegionScanner().scan(output, listOf(FaceFinder())) {}

        val durationUs = before.durationMs * 1_000
        val faceBefore = countSamplesWithFaces(covered, durationUs)
        val faceAfter = countSamplesWithFaces(leaked, durationUs)
        assumeTrue("Nothing was covered, nothing to check", faceBefore > 0)

        assertTrue(
            "$faceAfter of the $faceBefore face-bearing moments still show a detectable face " +
                "after blurring — the mosaic is missing them or landing somewhere else",
            faceAfter <= faceBefore * MAX_LEAK_FRACTION
        )
    }

    private fun countSamplesWithFaces(timeline: BlurTimeline, durationUs: Long): Int {
        val boxes = FloatArray(BlurTimeline.MAX_REGIONS * 4)
        val step = BlurTimeline.SAMPLE_PERIOD_MS * 1_000
        return (0 until durationUs step step).count { timeline.boxesAt(it, boxes) != 0 }
    }

    private class VideoInfo(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val durationMs: Long,
        val hasAudio: Boolean
    )

    private fun probe(file: File): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun meta(key: Int) = retriever.extractMetadata(key)
            VideoInfo(
                width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(),
                rotationDegrees = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0,
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(),
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            )
        } finally {
            retriever.release()
        }
    }

    private companion object {
        /**
         * Some leakage is expected: re-encoding shifts pixels a little and the detector is not
         * perfectly repeatable, so a face grazing the edge of the mosaic can still register.
         * Measured at 4 of 188 on the reference clip, so this leaves generous headroom while
         * still failing loudly if the blur stops covering faces.
         */
        const val MAX_LEAK_FRACTION = 0.15
    }

    /** These tests exercise the picture, not the audio; the censor is never switched on. */
    private fun noCensor(context: android.content.Context) =
        com.dmrandevu.gallery.media.censor.AudioCensor(
            context,
            com.dmrandevu.gallery.media.censor.CensorModels(context, okhttp3.OkHttpClient())
        )
}
