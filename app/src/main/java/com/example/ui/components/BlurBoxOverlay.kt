package com.example.ui.components

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.media.BlurHandle
import com.example.media.BlurRegion
import com.example.media.PreviewBlur
import com.example.ui.theme.SkyAccent
import kotlin.math.roundToInt

/**
 * A small, pre-blurred copy of the current video frame. The blur box draws the part of it that
 * it covers, scaled up, which follows the video in real time. The copy's size depends on the
 * blur strength (see [PreviewBlur]).
 */
class FrameSnapshot {
    var bitmap: Bitmap? = null
        private set

    /** Incremented after every capture. Read it while drawing so new frames are redrawn. */
    val version = mutableIntStateOf(0)

    private var pixels = IntArray(0)
    private var scratch = IntArray(0)

    /**
     * Copies the current frame of [textureView], blurred by [sigmaFraction] (Gaussian sigma as a
     * fraction of the picture's shorter side, see [com.example.media.BlurStrength]).
     * Must be called on the main thread.
     */
    fun capture(textureView: TextureView, sigmaFraction: Float): Boolean {
        if (!textureView.isAvailable || textureView.width <= 0 || textureView.height <= 0) return false
        val size = PreviewBlur.snapshotSize(textureView.width, textureView.height, sigmaFraction)
        val target = bitmap?.takeIf { it.width == size.width && it.height == size.height }
            ?: Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        try {
            textureView.getBitmap(target)
        } catch (e: Exception) {
            return false
        }
        blur(target)
        version.intValue++
        return true
    }

    private fun blur(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (pixels.size != n) {
            pixels = IntArray(n)
            scratch = IntArray(n)
        }
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        PreviewBlur.boxBlur(pixels, scratch, w, h)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

/**
 * The blur box shown over the video. Drag inside it to move it; drag a corner to resize it.
 * It fills the video picture, so [region] is relative to the picture (see [BlurRegion]).
 *
 * @param showFrame whether to draw the dashed outline and the corner handles.
 */
@Composable
fun BlurBoxOverlay(
    region: BlurRegion,
    showFrame: Boolean,
    snapshot: FrameSnapshot,
    onRegionChange: (BlurRegion) -> Unit,
    onRegionChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val latestRegion by rememberUpdatedState(region)
    val latestOnChange by rememberUpdatedState(onRegionChange)
    val latestOnFinished by rememberUpdatedState(onRegionChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    val frameVisible = showFrame || dragging

    BoxWithConstraints(modifier) {
        val frameW = constraints.maxWidth.toFloat()
        val frameH = constraints.maxHeight.toFloat()
        if (frameW <= 0f || frameH <= 0f) return@BoxWithConstraints

        val boxLeft = region.left * frameW
        val boxTop = region.top * frameH
        val boxWidth = region.width * frameW
        val boxHeight = region.height * frameH

        Box(
            modifier = Modifier
                .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
                .size(with(density) { boxWidth.toDp() }, with(density) { boxHeight.toDp() })
                .pointerInput(frameW, frameH) {
                    var working = latestRegion
                    detectDragGestures(
                        onDragStart = {
                            working = latestRegion
                            dragging = true
                        },
                        onDragEnd = {
                            dragging = false
                            latestOnFinished()
                        },
                        onDragCancel = {
                            dragging = false
                            latestOnFinished()
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        working = working.moveBy(dragAmount.x / frameW, dragAmount.y / frameH)
                        latestOnChange(working)
                    }
                }
                // While editing, a tap on the box shouldn't play/pause the video.
                .then(if (frameVisible) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
                .testTag("blur_box")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Reading the version makes this redraw whenever a new frame is captured.
                val version = snapshot.version.intValue
                val bmp = snapshot.bitmap
                if (bmp != null && version > 0) {
                    val srcLeft = (region.left * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
                    val srcTop = (region.top * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
                    val srcWidth = (region.width * bmp.width).roundToInt().coerceIn(1, bmp.width - srcLeft)
                    val srcHeight = (region.height * bmp.height).roundToInt().coerceIn(1, bmp.height - srcTop)
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset(srcLeft, srcTop),
                        srcSize = IntSize(srcWidth, srcHeight),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low
                    )
                } else {
                    // No frame yet: a frosted placeholder so the box is still visible.
                    drawRect(Color(0xD96B7280))
                }
                if (frameVisible) {
                    val strokeWidth = 1.5.dp.toPx()
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                        )
                    )
                }
            }
        }

        if (frameVisible) {
            val touchSize = 32.dp
            val touchSizePx = with(density) { touchSize.toPx() }
            for (handle in BlurHandle.entries) {
                val isLeft = handle == BlurHandle.TOP_LEFT || handle == BlurHandle.BOTTOM_LEFT
                val isTop = handle == BlurHandle.TOP_LEFT || handle == BlurHandle.TOP_RIGHT
                val centerX = if (isLeft) boxLeft else boxLeft + boxWidth
                val centerY = if (isTop) boxTop else boxTop + boxHeight
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (centerX - touchSizePx / 2).roundToInt(),
                                (centerY - touchSizePx / 2).roundToInt()
                            )
                        }
                        .size(touchSize)
                        .pointerInput(handle, frameW, frameH) {
                            var working = latestRegion
                            detectDragGestures(
                                onDragStart = {
                                    working = latestRegion
                                    dragging = true
                                },
                                onDragEnd = {
                                    dragging = false
                                    latestOnFinished()
                                },
                                onDragCancel = {
                                    dragging = false
                                    latestOnFinished()
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                working = working.resize(handle, dragAmount.x / frameW, dragAmount.y / frameH)
                                latestOnChange(working)
                            }
                        }
                        .testTag("blur_handle_${handle.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, SkyAccent, CircleShape)
                    )
                }
            }
        }
    }
}
