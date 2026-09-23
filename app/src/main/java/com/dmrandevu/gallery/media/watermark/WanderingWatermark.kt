package com.dmrandevu.gallery.media.watermark

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay

/**
 * Burns the account handle into the video on a slow, never-quite-repeating path around the frame.
 *
 * A corner watermark is one crop away from gone. This one visits the whole frame over a few
 * minutes, so there is no safe crop, while drifting slowly enough to read and to ignore. Each
 * instance takes its own [WanderPath], so no two videos start in the same place or move the same
 * way — which also means the preview and the export of one video do not follow the same path.
 */
@UnstableApi
class WanderingWatermark(
    handle: String,
    private val path: WanderPath = WanderPath()
) : TextOverlay() {

    private val text = SpannableString("@${handle.removePrefix("@")}").apply {
        setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // A translucent band behind the glyphs: white alone disappears against a bright sky.
        setSpan(BackgroundColorSpan(BACKDROP), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private var frame = Size(1, 1)

    override fun getText(presentationTimeUs: Long): SpannableString = text

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        frame = videoSize
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        // Asking for the bitmap rather than the texture size: this is called before the texture
        // exists on the first frame, and the bitmap is cached on the text so it costs nothing.
        val bitmap = getBitmap(presentationTimeUs)
        val scale = (WIDTH_FRACTION * frame.width / bitmap.width).coerceAtMost(1f)

        // How much of the frame the text covers, as a fraction of the half-frame that anchor
        // coordinates are measured in. Subtracting it keeps the whole label on screen.
        val reachX = (1f - bitmap.width.toFloat() / frame.width * scale - MARGIN).coerceAtLeast(0f)
        val reachY = (1f - bitmap.height.toFloat() / frame.height * scale - MARGIN).coerceAtLeast(0f)

        val seconds = presentationTimeUs / 1_000_000.0
        return StaticOverlaySettings.Builder()
            .setScale(scale, scale)
            .setAlphaScale(ALPHA)
            .setBackgroundFrameAnchor(
                (path.x(seconds) * reachX).toFloat(),
                (path.y(seconds) * reachY).toFloat()
            )
            .build()
    }

    private companion object {
        /** Share of the frame width the label spans. Big enough to read after Instagram's re-encode. */
        const val WIDTH_FRACTION = 0.34f

        const val ALPHA = 0.62f
        const val BACKDROP = 0x73000000.toInt()

        /** Keeps the label off the very edge, where players and crops eat into the frame. */
        const val MARGIN = 0.04f
    }
}
