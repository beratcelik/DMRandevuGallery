package com.dmrandevu.gallery.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.dmrandevu.gallery.media.blur.BlurEffect
import com.dmrandevu.gallery.media.blur.BlurTimeline
import com.dmrandevu.gallery.media.blur.FaceFinder
import com.dmrandevu.gallery.media.blur.PlateFinder
import com.dmrandevu.gallery.media.blur.RegionFinder
import com.dmrandevu.gallery.media.blur.RegionScanner
import com.dmrandevu.gallery.media.watermark.WanderingWatermark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Re-encodes a video with whatever [ExportOptions] asks for: faces mosaicked, licence plates
 * mosaicked, the account handle drifting across the picture, or any combination.
 *
 * Nothing here ever falls back to handing over the untouched video when something was asked for —
 * a silent unprotected export is exactly the failure these options exist to prevent, so a failed
 * pass throws instead.
 */
@UnstableApi
class VideoExporter(private val context: Context) {

    sealed interface Result {
        /**
         * [file] is the re-encoded video. [blurred] is what the scan actually covered, or null
         * when no blurring was asked for; it is worth handing back rather than re-running, since
         * a second scan would not agree with the first in every detail.
         */
        data class Exported(val file: File, val blurred: BlurTimeline?) : Result

        /** Nothing to change, so the input was left alone — the caller delivers it as-is. */
        data object NothingToDo : Result
    }

    class ExportFailedException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Applies [options] to [input], writing to [output]. [onProgress] reports 0..100. Cancelling
     * the calling coroutine stops the export and removes [output].
     *
     * Every failure surfaces as [ExportFailedException] so callers have one thing to catch and no
     * way to mistake a broken export for a finished one.
     */
    suspend fun export(
        input: File,
        output: File,
        options: ExportOptions,
        onProgress: (Int) -> Unit
    ): Result {
        if (options.changesNothing) return Result.NothingToDo

        val finders = buildList<RegionFinder> {
            if (options.blurFaces) add(FaceFinder())
            if (options.blurPlates) {
                add(PlateFinder(context, PlateFinder.inputSizeFor(options.fastPlates)))
            }
        }
        val scanShare = if (finders.isEmpty()) 0 else SCAN_SHARE
        val blurred = if (finders.isEmpty()) {
            null
        } else {
            wrapFailures("Scanning for faces and plates failed") {
                withContext(Dispatchers.Default) {
                    RegionScanner().scan(input, finders) { onProgress((it * scanShare).toInt()) }
                }
            }
        }

        val effects = buildList<Effect> {
            // An empty timeline means nothing to cover; adding the effect anyway would re-encode
            // the whole video to change nothing.
            if (blurred != null && !blurred.isEmpty) add(BlurEffect(blurred))
            options.watermarkHandle?.let { add(OverlayEffect(listOf(WanderingWatermark(it)))) }
        }
        if (effects.isEmpty()) return Result.NothingToDo

        wrapFailures("Export failed") { runExport(input, output, effects, scanShare, onProgress) }
        return Result.Exported(output, blurred)
    }

    private suspend fun <T> wrapFailures(message: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ExportFailedException) {
        throw e
    } catch (e: Exception) {
        throw ExportFailedException(message, e)
    }

    /**
     * Runs the Transformer export. Every touch of it — construction, start, progress, cancel —
     * has to happen on the main looper, which is why the whole body sits in [Dispatchers.Main].
     */
    private suspend fun runExport(
        input: File,
        output: File,
        effects: List<Effect>,
        progressFloor: Int,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.Main) {
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(/* audioProcessors= */ emptyList(), effects))
            .build()
        val sequence = EditedMediaItemSequence.Builder(editedMediaItem).build()
        val composition = Composition.Builder(sequence)
            // No audio effects, so the audio track is copied across rather than re-encoded.
            .setTransmuxAudio(true)
            // The shaders are ES 2.0 and cannot read HDR input. Tone mapping is a no-op for the
            // SDR videos Instagram actually delivers.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        var poller: Job? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    // Encode a portrait clip as portrait. Left off, media3 encodes it landscape
                    // and writes a rotation instead — correct, but players that ignore the
                    // rotation show the video on its side, and Instagram is not worth the gamble.
                    .setPortraitEncodingEnabled(true)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    ExportFailedException("Transformer failed", exception)
                                )
                            }
                        }
                    })
                    .build()
                transformer.start(composition, output.absolutePath)

                poller = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        delay(PROGRESS_POLL_MS)
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressFloor + holder.progress * (100 - progressFloor) / 100)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    // Cancellation arrives on whatever thread cancelled us, but Transformer only
                    // tolerates its own looper. This cannot go through `launch`: by now the scope
                    // is cancelled, so a new child would never run and the export would keep
                    // going with nobody left to stop it.
                    Handler(Looper.getMainLooper()).post {
                        runCatching { transformer.cancel() }
                        output.delete()
                    }
                }
            }
        } finally {
            poller?.cancel()
        }

        if (!output.exists() || output.length() == 0L) {
            throw ExportFailedException("Export produced no file")
        }
        onProgress(100)
    }

    private companion object {
        /** Share of the progress bar the scan gets; the export takes the rest. */
        const val SCAN_SHARE = 35
        const val PROGRESS_POLL_MS = 500L
    }
}
