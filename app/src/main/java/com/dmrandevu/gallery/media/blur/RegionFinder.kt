package com.dmrandevu.gallery.media.blur

import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage

/** Something that points at the parts of a frame that should not leave the phone readable. */
interface RegionFinder {

    /**
     * How often this finder wants to be shown a frame. Costed per finder because they are not
     * equally reliable: a face is found in most frames it appears in, a small plate in a minority
     * of them, so the plate pass needs more chances to make up the difference.
     */
    val samplePeriodMs: Long

    /** Regions to cover in [frame], normalized to whichever view of it the finder used. */
    suspend fun regionsIn(frame: ScannedFrame): List<BlurTimeline.Region>

    fun close()
}

/**
 * ML Kit reports boxes in the upright frame, which is the space [BlurTimeline] and the shader
 * both work in, so normalizing is just a division by [InputImage.getWidth]/[InputImage.getHeight]
 * — those already account for the rotation the image was created with.
 */
internal fun Rect.normalizedIn(frame: InputImage): RectF {
    val width = frame.width.toFloat()
    val height = frame.height.toFloat()
    return RectF(left / width, top / height, right / width, bottom / height)
}
