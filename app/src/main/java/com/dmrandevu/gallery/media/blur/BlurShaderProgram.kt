package com.dmrandevu.gallery.media.blur

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/**
 * Pixelates whatever the [BlurTimeline] says is a face at the frame being drawn, and leaves the
 * rest of the picture untouched.
 *
 * Mosaic rather than a gaussian blur: it is one texture fetch per pixel instead of a kernel, and
 * at a cell size around a seventh of the face there is nothing left to reconstruct.
 */
@UnstableApi
class BlurShaderProgram(
    useHdr: Boolean,
    private val timeline: BlurTimeline
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    /** Reused every frame so drawing allocates nothing. */
    private val faces = FloatArray(BlurTimeline.MAX_REGIONS * 4)

    private var facesLocation = -1

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        glProgram.setFloatsUniform("uTexSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val count = timeline.boxesAt(presentationTimeUs, faces)
            // The timeline speaks the detector's y-down bitmap space; GL samples bottom-up.
            // This is the one place the two conventions meet — if the blur ever comes out
            // vertically mirrored, this line is what to remove.
            for (i in 0 until count) faces[i * 4 + 1] = 1f - faces[i * 4 + 1]
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setIntUniform("uRegionCount", count)
            glProgram.bindAttributesAndUniforms()
            if (count > 0) {
                // GlProgram has no array-uniform setter, so this one goes in by hand. The location
                // is only resolvable once the program is linked and current.
                if (facesLocation < 0) facesLocation = glProgram.getUniformLocation(REGIONS_UNIFORM)
                GLES20.glUniform4fv(facesLocation, count, faces, /* offset= */ 0)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        /** Matches the arrays declared in the fragment shader, which drivers name `name[0]`. */
        const val REGIONS_UNIFORM = "uRegions[0]"
        const val SHAPES_UNIFORM = "uRegionIsRect[0]"

        val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexSize;
            // Face count for this frame, or -1 to mosaic everything (more faces than fit below).
            uniform int uRegionCount;
            // xy = centre, zw = half extents, normalized, y already flipped to GL's bottom-up axis.
            uniform vec4 uRegions[${BlurTimeline.MAX_REGIONS}];
            // 1.0 where the region is a numberplate and wants a rectangle, 0.0 for a head.
            uniform float uRegionIsRect[${BlurTimeline.MAX_REGIONS}];
            varying vec2 vTexSamplingCoord;

            vec4 mosaic(vec2 uv, vec2 cellPx) {
              vec2 cell = max(cellPx, vec2(2.0)) / uTexSize;
              vec2 snapped = (floor(uv / cell) + 0.5) * cell;
              return texture2D(uTexSampler, clamp(snapped, 0.0, 1.0));
            }

            void main() {
              vec2 uv = vTexSamplingCoord;
              vec4 color = texture2D(uTexSampler, uv);
              if (uRegionCount < 0) {
                gl_FragColor = mosaic(uv, uTexSize / $WHOLE_FRAME_CELLS);
                return;
              }
              for (int i = 0; i < ${BlurTimeline.MAX_REGIONS}; i++) {
                if (i >= uRegionCount) break;
                vec2 centre = uRegions[i].xy;
                vec2 half_extents = max(uRegions[i].zw, vec2(0.001));
                vec2 d = (uv - centre) / half_extents;
                // A head is covered by the ellipse inscribed in its box; a plate is a rectangle
                // and an ellipse over one would miss the first and last characters while
                // spilling over the bodywork above and below.
                float inside = uRegionIsRect[i] > 0.5
                    ? 1.0 - smoothstep(0.90, 1.0, max(abs(d.x), abs(d.y)))
                    : 1.0 - smoothstep(0.85, 1.0, dot(d, d));
                if (inside > 0.0) {
                  vec2 cellPx = max(half_extents * uTexSize * 2.0 / $MOSAIC_CELLS, vec2(6.0));
                  color = mix(color, mosaic(uv, cellPx), inside);
                }
              }
              gl_FragColor = color;
            }
        """.trimIndent()

        /** Mosaic cells across a region, as a GLSL literal. Coarse enough that nothing survives. */
        const val MOSAIC_CELLS = "7.0"

        /** Cells across the frame when there are too many faces to handle individually. */
        const val WHOLE_FRAME_CELLS = "24.0"
    }
}
