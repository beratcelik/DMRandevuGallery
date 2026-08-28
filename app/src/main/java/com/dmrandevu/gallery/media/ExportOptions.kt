package com.dmrandevu.gallery.media

/**
 * What should be changed about a video on its way out of the app.
 *
 * All off by default: an untouched video is delivered by streaming it straight through, which is
 * far quicker than the decode-and-re-encode any of these needs.
 */
data class ExportOptions(
    val blurFaces: Boolean = false,
    val blurPlates: Boolean = false,
    /** Whether the plate pass runs at the quicker, less thorough setting. */
    val fastPlates: Boolean = true,
    /** Account handle to burn into the picture, or null to leave the video unmarked. */
    val watermarkHandle: String? = null,
    /** Beep over Turkish swearing, leaving the background sound playing underneath. */
    val censorAudio: Boolean = false,
    /** Whether milder insults are beeped too, or only outright profanity. */
    val censorInsults: Boolean = false
) {
    val changesNothing: Boolean
        get() = !blurFaces && !blurPlates && watermarkHandle == null && !censorAudio

    companion object {
        val NONE = ExportOptions()
    }
}
