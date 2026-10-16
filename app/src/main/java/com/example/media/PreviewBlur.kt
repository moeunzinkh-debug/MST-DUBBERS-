package com.example.media

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The math behind the live blur preview: the player copies the current frame into a small
 * bitmap, box-blurs it and draws the part under the blur box scaled up.
 *
 * The bitmap size depends on the blur strength. A strong blur uses a tiny copy (cheap, and the
 * upscaling adds to the blur); a weak blur needs a bigger copy so that it stays sharp enough.
 * The blur then always spans about [TARGET_SIGMA_PX] pixels of the copy, which a box blur of
 * radius [BOX_RADIUS] reproduces well, and matches the Gaussian sigma of the exported video.
 */
object PreviewBlur {
    /** Blur sigma, in pixels of the small copy of the frame. */
    const val TARGET_SIGMA_PX = 1.5f

    /** Box blur radius; a box of 2 * radius + 1 pixels has a sigma of about 1.41 pixels. */
    const val BOX_RADIUS = 2

    /** Limits for the shorter side of the copy, in pixels. */
    const val MIN_SHORT_SIDE = 12
    const val MAX_SHORT_SIDE = 220

    /** Size of the frame copy for a view of [viewWidth] x [viewHeight] pixels. */
    fun snapshotSize(viewWidth: Int, viewHeight: Int, sigmaFraction: Float): Size {
        val w = viewWidth.coerceAtLeast(1)
        val h = viewHeight.coerceAtLeast(1)
        val shortSide = (TARGET_SIGMA_PX / sigmaFraction.coerceAtLeast(1e-4f))
            .coerceIn(MIN_SHORT_SIDE.toFloat(), MAX_SHORT_SIDE.toFloat())
        val scale = min(1f, shortSide / min(w, h))
        return Size(
            width = (w * scale).roundToInt().coerceAtLeast(1),
            height = (h * scale).roundToInt().coerceAtLeast(1)
        )
    }

    /**
     * Blurs the opaque ARGB [pixels] (a [width] x [height] image) in place with a box of
     * 2 * [radius] + 1 pixels, horizontally then vertically. Pixels past the edges repeat the
     * edge pixel. [scratch] must be at least as big as [pixels]. Runs in O(pixels) for any radius.
     */
    fun boxBlur(pixels: IntArray, scratch: IntArray, width: Int, height: Int, radius: Int = BOX_RADIUS) {
        if (radius <= 0 || width <= 0 || height <= 0) return
        blurPass(pixels, scratch, lines = height, length = width, lineStride = width, step = 1, radius = radius)
        blurPass(scratch, pixels, lines = width, length = height, lineStride = 1, step = width, radius = radius)
    }

    private fun blurPass(
        src: IntArray,
        dst: IntArray,
        lines: Int,
        length: Int,
        lineStride: Int,
        step: Int,
        radius: Int
    ) {
        val count = 2 * radius + 1
        val last = length - 1
        for (line in 0 until lines) {
            val base = line * lineStride
            var r = 0
            var g = 0
            var b = 0
            for (k in -radius..radius) {
                val c = src[base + k.coerceIn(0, last) * step]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (i in 0 until length) {
                dst[base + i * step] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                // Slide the window one pixel: drop the leftmost pixel, add the next one.
                val out = src[base + (i - radius).coerceIn(0, last) * step]
                val next = src[base + (i + radius + 1).coerceIn(0, last) * step]
                r += ((next shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((next shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (next and 0xFF) - (out and 0xFF)
            }
        }
    }

    data class Size(val width: Int, val height: Int)
}
