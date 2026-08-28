package com.dmrandevu.gallery.media

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
import java.io.File
import kotlin.math.abs

/**
 * A watermark-only export: no face detection, just the drifting handle burned in.
 *
 * Where the label sits at any instant is a judgement call best made by looking at the video; what
 * is worth pinning down here is that asking for a watermark alone actually re-encodes, and that
 * the export comes back the same shape, length and orientation it went in as.
 *
 * Needs the same sample clip as [com.dmrandevu.gallery.media.blur.FaceBlurExportTest];
 * skips without it.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class WatermarkExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample = File(context.getExternalFilesDir(null), "faceblur_test.mp4")

    @Test
    fun watermarkAloneStillExportsAnIntactVideo() = runBlocking {
        assumeTrue("No sample video at ${sample.path}", sample.exists())

        val input = File(context.cacheDir, "wm_input.mp4").also { sample.copyTo(it, true) }
        val output = File(context.cacheDir, "wm_output.mp4").also { it.delete() }

        val result = VideoExporter(context, noCensor(context)).export(
            input,
            output,
            ExportOptions(watermarkHandle = "trafik_cezasi")
        ) {}

        assertTrue(
            "A watermark on its own has to re-encode; got $result",
            result is VideoExporter.Result.Exported
        )
        assertEquals(
            "Nothing was detected, so nothing should be reported as blurred",
            null,
            (result as VideoExporter.Result.Exported).blurred
        )
        assertTrue("Output is empty", output.length() > 0)

        val before = probe(input)
        val after = probe(output)
        assertEquals("Width changed", before.width, after.width)
        assertEquals("Height changed", before.height, after.height)
        assertEquals("Rotation changed", before.rotationDegrees, after.rotationDegrees)
        assertEquals("Audio track lost", before.hasAudio, after.hasAudio)
        assertTrue(
            "Duration drifted: ${before.durationMs} -> ${after.durationMs}",
            abs(before.durationMs - after.durationMs) < 200
        )
    }

    @Test
    fun noOptionsMeansNoReEncode() = runBlocking {
        assumeTrue("No sample video at ${sample.path}", sample.exists())

        val input = File(context.cacheDir, "wm_input.mp4").also { sample.copyTo(it, true) }
        val output = File(context.cacheDir, "wm_none.mp4").also { it.delete() }

        val result = VideoExporter(context, noCensor(context)).export(input, output, ExportOptions.NONE) {}

        assertEquals(VideoExporter.Result.NothingToDo, result)
        assertTrue("Nothing was asked for, so nothing should have been written", !output.exists())
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

    /** These tests exercise the picture, not the audio; the censor is never switched on. */
    private fun noCensor(context: android.content.Context) =
        com.dmrandevu.gallery.media.censor.AudioCensor(
            context,
            com.dmrandevu.gallery.media.censor.CensorModels(context, okhttp3.OkHttpClient())
        )
}
