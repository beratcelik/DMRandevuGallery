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
import kotlin.math.sin

/**
 * Burns the account handle into the video on a slow, never-quite-repeating path around the frame.
 *
 * A corner watermark is one crop away from gone. This one visits the whole frame over a few
 * minutes, so there is no safe crop, while drifting slowly enough to read and to ignore.
 * The path is two sine waves whose periods do not divide into each other, which wanders without
 * ever jumping — a genuinely random position each frame would strobe and be unreadable.
 */
@UnstableApi
class WanderingWatermark(handle: String) : TextOverlay() {

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
                (sin(seconds * TAU / PERIOD_X_SECONDS) * reachX).toFloat(),
                (sin(seconds * TAU / PERIOD_Y_SECONDS + PHASE) * reachY).toFloat()
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

        // Coprime periods, so horizontal and vertical drift stay out of step and the path does
        // not settle into a short loop. Slow enough to sit still under the eye — a full sweep
        // across the frame takes about a quarter of a minute — while a clip of any length still
        // sees the label move well away from wherever it started.
        const val PERIOD_X_SECONDS = 31.0
        const val PERIOD_Y_SECONDS = 23.0
        const val PHASE = 1.3
        const val TAU = 2 * Math.PI
    }
}
