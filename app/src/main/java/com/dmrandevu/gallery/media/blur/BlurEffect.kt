package com.dmrandevu.gallery.media.blur

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Hangs [BlurShaderProgram] off a [androidx.media3.transformer.Transformer] export. */
@UnstableApi
class BlurEffect(private val timeline: BlurTimeline) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BlurShaderProgram(useHdr, timeline)

    /** A timeline with no faces would re-encode the whole video to change nothing. */
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = timeline.isEmpty
}
