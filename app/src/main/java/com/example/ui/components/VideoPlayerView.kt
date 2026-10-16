package com.example.ui.components

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.example.media.BlurRegion
import com.example.media.SeekRequest
import com.example.ui.theme.DarkAccent
import com.example.ui.theme.DarkMuted
import com.example.ui.theme.SkyAccent
import com.example.util.SubtitleParser
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToLong

/** Height of the picture area; the compact controls sit below it. */
private val PICTURE_HEIGHT = 200.dp

/** How often the blur preview grabs a new frame while playing. */
private const val BLUR_SNAPSHOT_INTERVAL_MS = 80L

@Composable
fun VideoPlayerView(
    videoPath: String,
    isPlaying: Boolean,
    onPlayPauseToggle: (Boolean) -> Unit,
    currentTimeSec: Double,
    onSeek: (Double) -> Unit,
    seekRequest: SeekRequest?,
    durationSec: Double,
    onDurationKnown: (Double) -> Unit,
    onTimeUpdate: (Double) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    activeSubtitleText: String,
    showSubtitles: Boolean,
    onToggleSubtitles: () -> Unit,
    blurEnabled: Boolean,
    blurRegion: BlurRegion,
    onBlurRegionChange: (BlurRegion) -> Unit,
    onBlurRegionChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    isDubPlaying: Boolean = false,
    originalVolume: Float = 1.0f,
    isTranscribing: Boolean = false
) {
    val context = LocalContext.current
    val videoFile = remember(videoPath) { videoPath.takeIf { it.isNotBlank() }?.let { File(it) } }
    val hasVideoFile = remember(videoFile) { videoFile?.exists() == true }

    val latestOnPlayPause by rememberUpdatedState(onPlayPauseToggle)
    val latestOnDurationKnown by rememberUpdatedState(onDurationKnown)
    val latestOnTimeUpdate by rememberUpdatedState(onTimeUpdate)
    val latestTimeSec by rememberUpdatedState(currentTimeSec)
    val latestDurationSec by rememberUpdatedState(durationSec)
    val latestBlurEnabled by rememberUpdatedState(blurEnabled)

    var videoAspect by remember(videoPath) { mutableFloatStateOf(16f / 9f) }
    var playbackError by remember(videoPath) { mutableStateOf<String?>(null) }
    val snapshot = remember(videoPath) { FrameSnapshot() }

    // One ExoPlayer per video file, starting where the editor currently is.
    val player: ExoPlayer? = remember(videoPath, hasVideoFile) {
        if (!hasVideoFile || videoFile == null) {
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                if (currentTimeSec > 0.0) seekTo((currentTimeSec * 1000).roundToLong())
                prepare()
            }
        }
    }
    // A fresh view per player, so a View is never re-attached to a second parent.
    val textureView = remember(player) { TextureView(context) }

    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            // When the picture surface appears after the first frame was decoded (on open, or
            // when coming back to the app), ExoPlayer only paints it once playback starts. A
            // tiny seek paints the current frame while paused.
            private var surfaceReady = false
            private var frameShown = false
            private var nudged = false

            private fun showFrameIfPaused() {
                if (surfaceReady && !frameShown && !nudged && !p.playWhenReady &&
                    p.playbackState == Player.STATE_READY
                ) {
                    nudged = true
                    val position = p.currentPosition
                    p.seekTo(if (position > 0) position - 1 else position + 1)
                }
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                val ready = width > 0 && height > 0
                if (ready != surfaceReady) {
                    surfaceReady = ready
                    frameShown = false
                    nudged = false
                }
                showFrameIfPaused()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && p.duration > 0) {
                    latestOnDurationKnown(p.duration / 1000.0)
                }
                if (playbackState == Player.STATE_READY) showFrameIfPaused()
                if (playbackState == Player.STATE_ENDED) {
                    latestOnPlayPause(false)
                    if (p.duration > 0) latestOnTimeUpdate(p.duration / 1000.0)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onRenderedFirstFrame() {
                frameShown = true
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Cannot play this video (${error.errorCodeName})"
                latestOnPlayPause(false)
            }
        }
        p.addListener(listener)
        p.setVideoTextureView(textureView)

        // Wrap ExoPlayer's surface listener to learn when a new frame reaches the view, and
        // refresh the blur preview right then (at most every BLUR_SNAPSHOT_INTERVAL_MS while
        // playing, immediately when paused).
        val exoSurfaceListener = textureView.surfaceTextureListener
        var lastSnapshotAt = 0L
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                exoSurfaceListener?.onSurfaceTextureAvailable(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                exoSurfaceListener?.onSurfaceTextureSizeChanged(surface, width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
                exoSurfaceListener?.onSurfaceTextureDestroyed(surface) ?: true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                exoSurfaceListener?.onSurfaceTextureUpdated(surface)
                if (!latestBlurEnabled) return
                val now = SystemClock.uptimeMillis()
                if (!p.isPlaying || now - lastSnapshotAt >= BLUR_SNAPSHOT_INTERVAL_MS) {
                    lastSnapshotAt = now
                    snapshot.capture(textureView)
                }
            }
        }

        onDispose {
            textureView.surfaceTextureListener = exoSurfaceListener
            p.removeListener(listener)
            p.clearVideoTextureView(textureView)
            p.release()
        }
    }

    // Play / pause.
    LaunchedEffect(player, isPlaying) {
        val p = player ?: return@LaunchedEffect
        if (isPlaying && p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = isPlaying
    }

    // Seek requests (tapping a line, the seek bar). Requests made before this player existed are
    // already covered by its start position.
    var lastSeekToken by remember(player) { mutableLongStateOf(seekRequest?.token ?: 0L) }
    LaunchedEffect(player, seekRequest) {
        val request = seekRequest ?: return@LaunchedEffect
        if (request.token <= lastSeekToken) return@LaunchedEffect
        lastSeekToken = request.token
        player?.seekTo((request.positionSec * 1000).roundToLong())
    }

    // Report the playback position while playing.
    LaunchedEffect(player, isPlaying, hasVideoFile) {
        if (player != null) {
            while (isPlaying) {
                latestOnTimeUpdate(player.currentPosition / 1000.0)
                delay(50)
            }
        } else if (!hasVideoFile) {
            // No video file: run a clock so subtitles and voices can still be previewed.
            var time = latestTimeSec
            var last = SystemClock.elapsedRealtime()
            while (isPlaying) {
                delay(50)
                val now = SystemClock.elapsedRealtime()
                time += (now - last) / 1000.0
                last = now
                val end = latestDurationSec
                if (end > 0 && time >= end) {
                    latestOnTimeUpdate(end)
                    latestOnPlayPause(false)
                    break
                }
                latestOnTimeUpdate(time)
            }
        }
    }

    // Original audio volume: muted, ducked to 10% while an AI voice plays, or the mix setting.
    val effectiveVolume = when {
        isMuted -> 0f
        isDubPlaying -> 0.10f
        else -> originalVolume.coerceIn(0f, 1f)
    }
    LaunchedEffect(player, effectiveVolume) {
        player?.volume = effectiveVolume
    }

    // Pause when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) latestOnPlayPause(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Turning the blur box on while paused: copy the frame that is already on screen.
    LaunchedEffect(player, blurEnabled) {
        if (player != null && blurEnabled) snapshot.capture(textureView)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
    ) {
        // Picture area: tap to play / pause.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PICTURE_HEIGHT)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onPlayPauseToggle(!isPlaying) }
                .testTag("video_picture_area"),
            contentAlignment = Alignment.Center
        ) {
            if (player != null) {
                // Exactly the size of the picture, so the blur box coordinates match the video.
                Box(modifier = Modifier.aspectRatio(videoAspect)) {
                    AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
                    if (blurEnabled) {
                        BlurBoxOverlay(
                            region = blurRegion,
                            showFrame = !isPlaying,
                            snapshot = snapshot,
                            onRegionChange = onBlurRegionChange,
                            onRegionChangeFinished = onBlurRegionChangeFinished,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "Video placeholder",
                        tint = DarkMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = if (videoPath.isNotBlank()) "Video file not found" else "No video selected",
                        color = DarkMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (showSubtitles && activeSubtitleText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp, start = 16.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = activeSubtitleText,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            playbackError?.let { message ->
                Text(
                    text = message,
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Hidden while the blur box is on, so it never covers the box.
            if (!isPlaying && !isTranscribing && !blurEnabled) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            if (isTranscribing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = DarkAccent, modifier = Modifier.size(32.dp))
                        Text(
                            text = "Transcribing with AI...",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        PlayerControls(
            isPlaying = isPlaying,
            onPlayPauseToggle = onPlayPauseToggle,
            currentTimeSec = currentTimeSec,
            durationSec = durationSec,
            onSeek = onSeek,
            isMuted = isMuted,
            onMuteToggle = onMuteToggle,
            showSubtitles = showSubtitles,
            onToggleSubtitles = onToggleSubtitles,
            isDucking = !isMuted && isDubPlaying
        )
    }
}

/** Compact control strip: seek bar, then play/pause + time on the left, CC, mute + duration on the right. */
@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    onPlayPauseToggle: (Boolean) -> Unit,
    currentTimeSec: Double,
    durationSec: Double,
    onSeek: (Double) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    showSubtitles: Boolean,
    onToggleSubtitles: () -> Unit,
    isDucking: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 10.dp, bottom = 4.dp)
    ) {
        ThinSeekBar(
            positionSec = currentTimeSec,
            durationSec = durationSec,
            onSeek = onSeek,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallControlButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = { onPlayPauseToggle(!isPlaying) },
                testTag = "player_play_pause_button"
            )
            Text(
                text = SubtitleParser.formatTimeDisplay(currentTimeSec),
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            if (isDucking) {
                Text(
                    text = "Video 10%",
                    color = Color(0xFF10B981),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0x3310B981))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            SmallControlButton(
                icon = if (showSubtitles) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                contentDescription = if (showSubtitles) "Hide subtitles" else "Show subtitles",
                onClick = onToggleSubtitles,
                tint = if (showSubtitles) SkyAccent else DarkMuted,
                testTag = "player_subtitles_button"
            )
            Spacer(modifier = Modifier.width(6.dp))
            @Suppress("DEPRECATION")
            SmallControlButton(
                icon = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                onClick = onMuteToggle,
                tint = if (isMuted) Color(0xFFEF4444) else Color.White,
                testTag = "player_mute_button"
            )
            Text(
                text = SubtitleParser.formatTimeDisplay(durationSec),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

@Composable
private fun SmallControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 30.dp,
    testTag: String = ""
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
    }
}

/** A slim seek bar: tap to jump, or drag the thumb. */
@Composable
private fun ThinSeekBar(
    positionSec: Double,
    durationSec: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestDuration by rememberUpdatedState(durationSec)
    val fraction = scrubFraction
        ?: if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (latestDuration > 0) {
                        latestOnSeek((offset.x / size.width).coerceIn(0f, 1f) * latestDuration)
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> scrubFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        val f = scrubFraction
                        if (f != null && latestDuration > 0) latestOnSeek(f * latestDuration)
                        scrubFraction = null
                    },
                    onDragCancel = { scrubFraction = null },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scrubFraction = ((scrubFraction ?: 0f) + dragAmount / size.width).coerceIn(0f, 1f)
                    }
                )
            }
            .testTag("video_timeline_scrubber")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeight = 3.dp.toPx()
            val centerY = size.height / 2
            val radius = (if (scrubFraction != null) 7.dp else 5.5.dp).toPx()
            val thumbX = (size.width * fraction).coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2)
            )
            drawRoundRect(
                color = DarkAccent,
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = Size(size.width * fraction, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2)
            )
            drawCircle(color = Color.White, radius = radius, center = Offset(thumbX, centerY))
        }
    }
}
