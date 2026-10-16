package com.example.media

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Gaussian blur restricted to a [BlurRegion] of the video frame, used to hide burned-in text
 * when exporting. It runs as two passes (horizontal, then vertical); use [create] to get both.
 */
@OptIn(UnstableApi::class)
class RegionBlurEffect private constructor(
    private val region: BlurRegion,
    private val horizontal: Boolean,
    private val sigmaFraction: Float
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        RegionBlurShaderProgram(useHdr, region, horizontal, sigmaFraction)

    companion object {
        /** Default blur strength: the Gaussian sigma as a fraction of the frame's shorter side. */
        val DEFAULT_SIGMA_FRACTION: Float = BlurStrength.sigmaFraction(BlurStrength.DEFAULT)

        /** Most samples on each side of the pixel; must match the loop bound in the shader. */
        const val MAX_TAPS = 24
        const val MIN_TAPS = 8

        /** Preferred largest gap between two samples, in pixels (strong blurs need more samples). */
        const val MAX_STEP_PX = 3f

        /** Samples on each side of the pixel for a blur of [sigmaPx] pixels. */
        fun tapsFor(sigmaPx: Float): Int = ceil(3f * sigmaPx / MAX_STEP_PX).toInt().coerceIn(MIN_TAPS, MAX_TAPS)

        /** Returns the two passes that blur [region] (normalized, top-left origin). */
        fun create(region: BlurRegion, sigmaFraction: Float = DEFAULT_SIGMA_FRACTION): List<Effect> {
            val safeRegion = region.sanitized()
            return listOf(
                RegionBlurEffect(safeRegion, horizontal = true, sigmaFraction = sigmaFraction),
                RegionBlurEffect(safeRegion, horizontal = false, sigmaFraction = sigmaFraction)
            )
        }
    }
}

@OptIn(UnstableApi::class)
private class RegionBlurShaderProgram(
    useHdr: Boolean,
    private val region: BlurRegion,
    private val horizontal: Boolean,
    private val sigmaFraction: Float
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    init {
        // Draw the frame on the whole normalized device coordinate space, from -1 to 1.
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        // Texture coordinates have their origin at the bottom-left; the region's is top-left.
        glProgram.setFloatsUniform(
            "uRect",
            floatArrayOf(region.left, 1f - region.bottom, region.right, 1f - region.top)
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val sigmaPx = max(1.5f, sigmaFraction * min(inputWidth, inputHeight))
        val taps = RegionBlurEffect.tapsFor(sigmaPx)
        // The taps on each side of the pixel cover +/- 3 sigma.
        val stepPx = 3f * sigmaPx / taps
        glProgram.setFloatsUniform(
            "uStep",
            if (horizontal) floatArrayOf(stepPx / inputWidth, 0f) else floatArrayOf(0f, stepPx / inputHeight)
        )
        glProgram.setFloatUniform("uSigmaSteps", sigmaPx / stepPx)
        glProgram.setFloatUniform("uTaps", taps.toFloat())
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.bindAttributesAndUniforms()
            // The four-vertex triangle strip forms a quad.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
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
        const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        const val FRAGMENT_SHADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
// Blurred area in texture coordinates: left, bottom, right, top.
uniform vec4 uRect;
// Offset between two taps, in texture coordinates (horizontal or vertical pass).
uniform vec2 uStep;
// Gaussian sigma, in taps.
uniform float uSigmaSteps;
// Samples used on each side of the pixel (at most 24, the loop bound below).
uniform float uTaps;
varying vec2 vTexSamplingCoord;

void main() {
  vec2 coord = vTexSamplingCoord;
  if (coord.x < uRect.x || coord.x > uRect.z || coord.y < uRect.y || coord.y > uRect.w) {
    gl_FragColor = texture2D(uTexSampler, coord);
    return;
  }
  float twoSigmaSq = 2.0 * uSigmaSteps * uSigmaSteps;
  vec4 sum = texture2D(uTexSampler, coord);
  float total = 1.0;
  for (int i = 1; i <= 24; i++) {
    float fi = float(i);
    if (fi > uTaps) break;
    float w = exp(-(fi * fi) / twoSigmaSq);
    sum += w * (texture2D(uTexSampler, coord + uStep * fi) + texture2D(uTexSampler, coord - uStep * fi));
    total += 2.0 * w;
  }
  gl_FragColor = sum / total;
}
"""
    }
}
