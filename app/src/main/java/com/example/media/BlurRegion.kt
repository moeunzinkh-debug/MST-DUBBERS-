package com.example.media

import kotlin.math.max
import kotlin.math.min

/**
 * The blur box, as a rectangle in normalized coordinates of the displayed video frame:
 * (0, 0) is the top-left corner of the picture and (1, 1) the bottom-right corner.
 *
 * Normalized coordinates make the same box valid for the small preview player and for the
 * full-resolution video export.
 */
data class BlurRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Returns this region inside the frame and at least [MIN_SIZE] wide and tall. */
    fun sanitized(): BlurRegion {
        if (left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && width >= MIN_SIZE && height >= MIN_SIZE) {
            return this
        }
        val w = width.clampTo(MIN_SIZE, 1f)
        val h = height.clampTo(MIN_SIZE, 1f)
        val l = left.clampTo(0f, 1f - w)
        val t = top.clampTo(0f, 1f - h)
        return BlurRegion(l, t, l + w, t + h)
    }

    /** Moves the box by a normalized delta, keeping its size and staying inside the frame. */
    fun moveBy(dx: Float, dy: Float): BlurRegion {
        val w = width
        val h = height
        val l = (left + dx).clampTo(0f, 1f - w)
        val t = (top + dy).clampTo(0f, 1f - h)
        return BlurRegion(l, t, l + w, t + h)
    }

    /** Drags one corner by a normalized delta. The opposite corner stays where it is. */
    fun resize(handle: BlurHandle, dx: Float, dy: Float): BlurRegion {
        var l = left
        var t = top
        var r = right
        var b = bottom
        when (handle) {
            BlurHandle.TOP_LEFT -> {
                l = (l + dx).clampTo(0f, r - MIN_SIZE)
                t = (t + dy).clampTo(0f, b - MIN_SIZE)
            }
            BlurHandle.TOP_RIGHT -> {
                r = (r + dx).clampTo(l + MIN_SIZE, 1f)
                t = (t + dy).clampTo(0f, b - MIN_SIZE)
            }
            BlurHandle.BOTTOM_LEFT -> {
                l = (l + dx).clampTo(0f, r - MIN_SIZE)
                b = (b + dy).clampTo(t + MIN_SIZE, 1f)
            }
            BlurHandle.BOTTOM_RIGHT -> {
                r = (r + dx).clampTo(l + MIN_SIZE, 1f)
                b = (b + dy).clampTo(t + MIN_SIZE, 1f)
            }
        }
        return BlurRegion(l, t, r, b)
    }

    /** Serializes as `left,top,right,bottom` (see [decode]). */
    fun encode(): String = "$left,$top,$right,$bottom"

    companion object {
        /** Smallest allowed width/height (5% of the frame). */
        const val MIN_SIZE = 0.05f

        /** A wide band near the bottom, where burned-in subtitles usually are. */
        val DEFAULT = BlurRegion(left = 0.08f, top = 0.72f, right = 0.92f, bottom = 0.88f)

        /** Parses a value produced by [encode]; returns null if it is missing or malformed. */
        fun decode(value: String?): BlurRegion? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(',').map { it.trim().toFloatOrNull() }
            if (parts.size != 4 || parts.any { it == null || !it.isFinite() }) return null
            return BlurRegion(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!).sanitized()
        }
    }
}

/** The four corner handles of the blur box. */
enum class BlurHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Per-project blur box state. */
data class BlurSettings(
    val enabled: Boolean = false,
    val region: BlurRegion = BlurRegion.DEFAULT
)

/** Like coerceIn, but never throws when the range is empty (returns [low] instead). */
private fun Float.clampTo(low: Float, high: Float): Float = max(low, min(this, high))
