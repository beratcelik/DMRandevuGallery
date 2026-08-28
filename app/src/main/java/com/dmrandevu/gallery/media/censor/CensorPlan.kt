package com.dmrandevu.gallery.media.censor

import androidx.media3.common.util.UnstableApi

/**
 * Everything the export needs to know about censoring one video: where the swearing is, and the
 * audio to put there instead.
 *
 * Worked out once and carried around, rather than recomputed. Finding the swearing is the slow
 * part of the whole filter, and the answer is the same whether the operator is previewing the
 * video or exporting it — so a plan made for one is reused by the other.
 */
@UnstableApi
class CensorPlan(
    val sampleRate: Int,
    val channelCount: Int,
    /** Frames in the source audio, as decoded. The processor checks it sees the same number. */
    val sourceFrameCount: Long,
    val windows: List<CensorWindow>,
    val patches: List<BeepPatcher.PcmPatch>
) {
    val isEmpty: Boolean get() = patches.isEmpty()

    companion object {
        /** Nothing to censor — no audio track, or no swearing in it. */
        fun nothing() = CensorPlan(0, 0, 0, emptyList(), emptyList())
    }
}
