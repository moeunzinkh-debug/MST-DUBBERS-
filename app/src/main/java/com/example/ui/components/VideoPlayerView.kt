package com.example.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.DarkAccent
import com.example.ui.theme.DarkMuted
import com.example.util.SubtitleParser
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun VideoPlayerView(
    videoPath: String,
    isPlaying: Boolean,
    onPlayPauseToggle: (Boolean) -> Unit,
    currentTimeSec: Double,
    onSeek: (Double) -> Unit,
    durationSec: Double,
    onDurationKnown: (Double) -> Unit,
    onTimeUpdate: (Double) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    activeSubtitleText: String,
    showSubtitles: Boolean,
    isDubPlaying: Boolean = false,
    originalVolume: Float = 1.0f,
    isTranscribing: Boolean = false,
    modifier: Modifier = Modifier
) {
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val videoFile = remember(videoPath) {
        if (videoPath.isNotBlank()) File(videoPath) else null
    }
    val hasVideoFile = videoFile != null && videoFile.exists()

    // Audio ducking calculation:
    // If isMuted: 0% volume
    // If isMuted is false (unmuted):
    //   - When AI dubbed voice plays (isDubPlaying): original video audio ducks to 10% (0.10f)
    //   - When no dub audio is playing: original video audio plays at normal volume (100% or originalVolume)
    val effectiveVolume = remember(isMuted, isDubPlaying, originalVolume) {
        when {
            isMuted -> 0f
            isDubPlaying -> 0.10f // Duck original video sound to 10%
            else -> originalVolume.coerceIn(0f, 1f)
        }
    }

    // Apply volume dynamically to underlying MediaPlayer
    LaunchedEffect(effectiveVolume, mediaPlayerRef) {
        try {
            mediaPlayerRef?.setVolume(effectiveVolume, effectiveVolume)
        } catch (e: Exception) {
            // Ignore
        }
    }

    // Sync play/pause with VideoView
    LaunchedEffect(isPlaying) {
        videoViewRef?.let { vv ->
            if (isPlaying && !vv.isPlaying) {
                vv.start()
            } else if (!isPlaying && vv.isPlaying) {
                vv.pause()
            }
        }
    }

    // Periodic time polling while playing (supports both video and placeholder/sample ticker)
    LaunchedEffect(isPlaying, isUserSeeking, hasVideoFile) {
        if (!hasVideoFile) {
            var lastTime = System.currentTimeMillis()
            while (isPlaying && !isUserSeeking) {
                delay(50)
                val now = System.currentTimeMillis()
                val deltaSec = (now - lastTime) / 1000.0
                lastTime = now
                val nextTime = currentTimeSec + deltaSec
                if (durationSec > 0 && nextTime >= durationSec) {
                    onPlayPauseToggle(false)
                    onTimeUpdate(durationSec)
                    break
                } else {
                    onTimeUpdate(nextTime)
                }
            }
        } else {
            while (isPlaying && !isUserSeeking) {
                videoViewRef?.let { vv ->
                    val currentMs = vv.currentPosition
                    val durMs = vv.duration
                    if (durMs > 0 && durationSec <= 0.0) {
                        onDurationKnown(durMs / 1000.0)
                    }
                    onTimeUpdate(currentMs / 1000.0)
                }
                delay(50)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
    ) {
        // Video display box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onPlayPauseToggle(!isPlaying)
                },
            contentAlignment = Alignment.Center
        ) {
            if (hasVideoFile) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.fromFile(videoFile))
                            setOnPreparedListener { mp ->
                                mediaPlayerRef = mp
                                val dur = mp.duration / 1000.0
                                onDurationKnown(dur)
                                try {
                                    mp.setVolume(effectiveVolume, effectiveVolume)
                                } catch (e: Exception) {
                                    // Ignore
                                }
                                if (isPlaying) mp.start()
                            }
                            setOnCompletionListener {
                                onPlayPauseToggle(false)
                            }
                            videoViewRef = this
                        }
                    },
                    update = { vv ->
                        videoViewRef = vv
                    }
                )
            } else {
                // Stylish Fallback/Placeholder if video not yet selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "Video placeholder",
                        tint = DarkMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = if (videoPath.isNotBlank()) "Video file ready" else "No video selected",
                        color = DarkMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Big Play Overlay icon when paused
            if (!isPlaying && !isTranscribing) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            // Subtitle overlay
            if (showSubtitles && activeSubtitleText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp, start = 20.dp, end = 20.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = activeSubtitleText,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Transcribing indicator
            if (isTranscribing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = DarkAccent,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Transcribing with Gemini AI...",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }

        // Controls bar: Scrubber slider, Play/Pause, Time, Mute
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            val maxDur = maxOf(durationSec.toFloat(), 1f)
            val currentSliderVal = if (isUserSeeking) seekPosition else currentTimeSec.toFloat()

            Slider(
                value = currentSliderVal.coerceIn(0f, maxDur),
                onValueChange = {
                    isUserSeeking = true
                    seekPosition = it
                },
                onValueChangeFinished = {
                    isUserSeeking = false
                    videoViewRef?.seekTo((seekPosition * 1000).toInt())
                    onSeek(seekPosition.toDouble())
                },
                valueRange = 0f..maxDur,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = DarkAccent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video_timeline_scrubber")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onPlayPauseToggle(!isPlaying) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .testTag("player_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = SubtitleParser.formatTimeDisplay(currentTimeSec),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isMuted && isDubPlaying) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x3310B981))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "Video 10%",
                                color = Color(0xFF10B981),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = onMuteToggle,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .testTag("player_mute_button")
                    ) {
                        @Suppress("DEPRECATION")
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = if (isMuted) Color(0xFFEF4444) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = SubtitleParser.formatTimeDisplay(durationSec),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef?.stopPlayback()
        }
    }
}
