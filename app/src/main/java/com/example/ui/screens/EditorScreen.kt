package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SettingsRepository
import com.example.data.db.SegmentEntity
import com.example.media.ExportKind
import com.example.ui.DubberViewModel
import com.example.ui.VoiceIssue
import com.example.ui.components.AudioMixSheet
import com.example.ui.components.BlurStrengthPanel
import com.example.ui.components.EditSegmentSheet
import com.example.ui.components.ExportDialog
import com.example.ui.components.ExportSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.ShiftTimelineDialog
import com.example.ui.components.SpeedAdjustSheet
import com.example.ui.components.VideoPlayerView
import com.example.ui.components.VoiceSelectionSheet
import com.example.ui.theme.DarkAccent
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkBg2
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCard2
import com.example.ui.theme.DarkFg
import com.example.ui.theme.DarkLine
import com.example.ui.theme.DarkMuted
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.FuchsiaAccent
import com.example.ui.theme.FuchsiaAccentBg
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.SkyAccentBg
import com.example.ui.theme.VoiceButtonBg
import com.example.ui.theme.VoiceButtonFg
import com.example.util.SubtitleParser
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    projectId: Long,
    viewModel: DubberViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    BackHandler {
        onNavigateBack()
    }

    val project by viewModel.currentProject.collectAsState()
    val segments by viewModel.currentSegments.collectAsState()
    val selectedIds by viewModel.selectedSegmentIds.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val currentTime by viewModel.currentTime.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()

    val translatingProgress by viewModel.translatingProgress.collectAsState()
    val voicingProgress by viewModel.voicingProgress.collectAsState()
    val isTranscribing by viewModel.isTranscribing.collectAsState()
    val busyIds by viewModel.busySegmentIds.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val isDubPlaying by viewModel.isDubPlaying.collectAsState()
    val currentlyPlayingSegmentId by viewModel.currentlyPlayingSegmentId.collectAsState()
    val seekRequest by viewModel.seekRequest.collectAsState()
    val blurSettings by viewModel.blurSettings.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val voiceIssues by viewModel.voiceIssues.collectAsState()
    val haptics = LocalHapticFeedback.current

    // Stop playback when leaving the editor.
    DisposableEffect(Unit) {
        onDispose { viewModel.setPlaying(false) }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showBlurPanel by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showShiftTimelineDialog by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<SegmentEntity?>(null) }
    var speedAdjustSegment by remember { mutableStateOf<SegmentEntity?>(null) }

    LaunchedEffect(toastMessage) {
        val message = toastMessage?.first ?: return@LaunchedEffect
        // Long messages (e.g. the voice generation summary) stay longer; a tap closes them.
        delay((3_000L + message.length * 45L).coerceAtMost(10_000L))
        viewModel.clearToast()
    }

    val activeSegment = remember(segments, currentTime) {
        segments.find { currentTime >= it.start && currentTime < it.end }
    }

    // Follow the playing line, but don't move the list if it is already fully visible
    // (e.g. right after the user tapped it).
    LaunchedEffect(activeSegment?.id, isPlaying) {
        val activeId = activeSegment?.id
        if (!isPlaying || activeId == null) return@LaunchedEffect
        val idx = segments.indexOfFirst { it.id == activeId }
        if (idx < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == idx }
        val fullyVisible = item != null &&
            item.offset >= layout.viewportStartOffset &&
            item.offset + item.size <= layout.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(idx)
    }

    val subtitleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = BufferedReader(InputStreamReader(stream)).readText()
                    viewModel.importSubtitles(content)
                }
            } catch (e: Exception) {
                viewModel.showToast("Cannot read subtitle file: ${e.message}", true)
            }
        }
    }

    val translatedCount = remember(segments) { segments.count { !it.translation.isNullOrBlank() } }
    val voicedCount = remember(segments) { segments.count { !it.audioPath.isNullOrBlank() } }
    val allSelected = segments.isNotEmpty() && selectedIds.size == segments.size

    val overlayText = activeSegment?.let { it.translation?.takeIf { t -> t.isNotBlank() } ?: it.text } ?: ""

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg2)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("editor_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = DarkFg
                    )
                }

                Text(
                    text = project?.name ?: "Studio",
                    color = DarkFg,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                // Audio Mix Sheet Button
                IconButton(onClick = { showAudioSheet = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Audio Mix",
                        tint = DarkFg
                    )
                }

                // Blur box: hides burned-in text, in the preview and in the exported video.
                // Tap: on / off. Hold: blur strength panel.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (blurSettings.enabled) SkyAccent.copy(alpha = 0.18f) else Color.Transparent)
                        .combinedClickable(
                            onClickLabel = if (blurSettings.enabled) "Remove blur box" else "Add blur box",
                            onLongClickLabel = "Blur strength",
                            role = Role.Button,
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showBlurPanel = true
                            },
                            onClick = {
                                if (!blurSettings.enabled) {
                                    viewModel.showToast("Blur box on: drag it over the text, drag a corner to resize. Hold the blur icon to change the strength.")
                                }
                                viewModel.toggleBlur()
                            }
                        )
                        .testTag("editor_blur_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BlurOn,
                        contentDescription = if (blurSettings.enabled) "Remove blur box" else "Add blur box",
                        tint = if (blurSettings.enabled) SkyAccent else DarkFg
                    )
                }

                // Voice Personas Sheet Button
                IconButton(onClick = { showVoiceSheet = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = "Voices",
                        tint = DarkFg
                    )
                }

                // Overflow Menu
                Box {
                    IconButton(onClick = { showMenu = !showMenu }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = DarkFg
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(DarkBg2)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Transcribe with AI", color = DarkFg) },
                            leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DarkAccent) },
                            onClick = {
                                showMenu = false
                                viewModel.startTranscribeAI()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import .SRT / .VTT", color = DarkFg) },
                            leadingIcon = { Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = DarkMuted) },
                            onClick = {
                                showMenu = false
                                subtitleLauncher.launch("*/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export SRT", color = DarkFg) },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = DarkMuted) },
                            onClick = {
                                showMenu = false
                                shareText(context, viewModel.exportSrt(false), "${project?.name ?: "subs"}.srt")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (settings.showSubtitles) "Hide Subtitles Overlay" else "Show Subtitles Overlay", color = DarkFg) },
                            leadingIcon = { Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = DarkMuted) },
                            onClick = {
                                showMenu = false
                                viewModel.settingsRepository.toggleShowSubtitles()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Shift Timeline (រំកិលម៉ោងទាំងអស់)", color = DarkFg) },
                            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, tint = SkyAccent) },
                            onClick = {
                                showMenu = false
                                showShiftTimelineDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings", color = DarkFg) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = DarkMuted) },
                            onClick = {
                                showMenu = false
                                showSettingsSheet = true
                            }
                        )
                        HorizontalDivider(color = DarkLine)
                        DropdownMenuItem(
                            text = { Text("Delete Project", color = Color.Red) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) },
                            onClick = {
                                showMenu = false
                                viewModel.deleteCurrentProject(onNavigateBack)
                            }
                        )
                    }
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg2)
                    .border(width = 1.dp, color = DarkLine)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Button 1: Translate with AI
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SkyAccent)
                        .clickable(enabled = translatingProgress == null && segments.isNotEmpty()) {
                            viewModel.startBatchTranslate()
                        }
                        .testTag("batch_translate_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (translatingProgress != null) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Translate",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Translate AI",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Text(
                            text = translatingProgress?.let { "${it.done}/${it.total}" } ?: "$translatedCount/${segments.size}",
                            color = Color.Black.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Button 2: Voice with AI (TTS)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(VoiceButtonBg)
                        // While voicing, a tap stops the run.
                        .clickable(enabled = voicingProgress != null || segments.isNotEmpty()) {
                            viewModel.startBatchVoice()
                        }
                        .testTag("batch_voice_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (voicingProgress != null) {
                            CircularProgressIndicator(
                                color = VoiceButtonFg,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Voice AI",
                                tint = VoiceButtonFg,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = if (voicingProgress != null) "Tap to stop" else "Edge TTS Dub",
                            color = VoiceButtonFg,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Text(
                            text = voicingProgress?.let { p ->
                                when {
                                    p.retryInSec != null -> "${p.done}/${p.total} · retry ${p.retryInSec}s"
                                    p.failed > 0 -> "${p.done}/${p.total} · ${p.failed} failed"
                                    else -> "${p.done}/${p.total}"
                                }
                            } ?: if (selectedIds.isNotEmpty()) "${selectedIds.size} selected" else "$voicedCount/${segments.size}",
                            maxLines = 1,
                            color = VoiceButtonFg.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Button 3: Export
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkCard2)
                        .clickable { showExportSheet = true }
                        .testTag("open_export_sheet_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export",
                            tint = DarkMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Export",
                            color = DarkFg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Text(
                            text = "Dub & Subs",
                            color = DarkMuted,
                            fontSize = 11.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                VideoPlayerView(
                    videoPath = project?.videoPath ?: "",
                    isPlaying = isPlaying,
                    onPlayPauseToggle = { viewModel.setPlaying(it) },
                    currentTimeSec = currentTime,
                    onSeek = { viewModel.seekTo(it) },
                    seekRequest = seekRequest,
                    durationSec = duration,
                    onDurationKnown = { viewModel.setDuration(it) },
                    onTimeUpdate = { viewModel.setTime(it) },
                    isMuted = isMuted,
                    onMuteToggle = { viewModel.toggleMute() },
                    activeSubtitleText = overlayText,
                    showSubtitles = settings.showSubtitles,
                    onToggleSubtitles = { viewModel.settingsRepository.toggleShowSubtitles() },
                    blurEnabled = blurSettings.enabled,
                    blurRegion = blurSettings.region,
                    blurStrength = blurSettings.strength,
                    onBlurRegionChange = { viewModel.updateBlurRegion(it) },
                    onBlurRegionChangeFinished = { viewModel.commitBlurRegion() },
                    isDubPlaying = isDubPlaying,
                    originalVolume = settings.originalVolume,
                    isTranscribing = isTranscribing
                )

                // Blur strength panel (hold the blur icon). It opens below the player, so the blur
                // box stays visible while the strength changes.
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    DropdownMenu(
                        expanded = showBlurPanel,
                        onDismissRequest = {
                            showBlurPanel = false
                            viewModel.commitBlurSettings()
                        },
                        modifier = Modifier.background(DarkBg2)
                    ) {
                        BlurStrengthPanel(
                            strength = blurSettings.strength,
                            blurOn = blurSettings.enabled,
                            onStrengthChange = { viewModel.updateBlurStrength(it) },
                            onStrengthChangeFinished = { viewModel.commitBlurSettings() },
                            onBlurOnChange = { viewModel.setBlurEnabled(it) }
                        )
                    }
                }
            }

            // Stats & Auto-Fit Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg2)
                    .border(width = 1.dp, color = DarkLine)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (allSelected) DarkAccent else DarkCard)
                        .border(1.dp, if (allSelected) DarkAccent else DarkLine, RoundedCornerShape(5.dp))
                        .clickable { viewModel.toggleSelectAll() },
                    contentAlignment = Alignment.Center
                ) {
                    if (allSelected) {
                        Icon(Icons.Default.Check, contentDescription = "All", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.ClosedCaption, contentDescription = "Subs", tint = DarkMuted, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("${segments.size}", color = DarkFg, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Translate, contentDescription = "Tr", tint = SkyAccent, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("$translatedCount", color = SkyAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.GraphicEq, contentDescription = "Voiced", tint = EmeraldAccent, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("$voicedCount", color = EmeraldAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp)
                }

                Spacer(modifier = Modifier.weight(1f))

                val fitMode = SettingsRepository.FIT_MODES.getOrNull(settings.fitIndex)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkCard2)
                        .clickable {
                            val nextIndex = (settings.fitIndex + 1) % SettingsRepository.FIT_MODES.size
                            viewModel.settingsRepository.updateFitIndex(nextIndex)
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Auto-Fit ${fitMode?.label ?: "Off"}",
                        color = DarkMuted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Segments Timeline List
            if (segments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ClosedCaption,
                            contentDescription = "Empty",
                            tint = DarkMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No subtitles found yet",
                            color = DarkFg,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Text(
                            text = "Transcribe with AI (${if (settings.transcriptionEngine == "groq_whisper") "Groq Whisper" else "Gemini"}) or import .SRT file",
                            color = DarkMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkAccent)
                                    .clickable(enabled = !isTranscribing) { viewModel.startTranscribeAI() }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text("Transcribe with AI", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkCard2)
                                    .clickable { subtitleLauncher.launch("*/*") }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text("Import .SRT", color = DarkFg, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("segment_list")
                ) {
                    items(segments, key = { it.id }) { seg ->
                        val isActive = activeSegment?.id == seg.id
                        val isSelected = selectedIds.contains(seg.id)
                        val isBusy = busyIds.contains(seg.id)
                        val isPlayingThisSegment = currentlyPlayingSegmentId == seg.id

                        SegmentRowItem(
                            segment = seg,
                            isActive = isActive,
                            isSelected = isSelected,
                            isBusy = isBusy,
                            isPlayingThisSegment = isPlayingThisSegment,
                            voiceIssue = voiceIssues[seg.id]?.takeIf { seg.audioPath.isNullOrBlank() },
                            onVoiceIssueClick = { viewModel.showVoiceIssue(seg) },
                            onSelect = { viewModel.toggleSegmentSelection(seg.id) },
                            onRowClick = { viewModel.seekToSegment(seg) },
                            onGenderToggle = {
                                val nextGender = if (seg.gender.equals("male", ignoreCase = true)) "female" else "male"
                                viewModel.updateSegment(seg.copy(gender = nextGender))
                            },
                            onEditClick = { editingSegment = seg },
                            onSpeedClick = { speedAdjustSegment = seg },
                            onPlayClick = { viewModel.previewOrGenerateAudio(seg) }
                        )
                        HorizontalDivider(color = DarkLine, thickness = 0.5.dp)
                    }
                }
            }
        }

        // Floating Toast Notification
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            toastMessage?.let { (msg, isErr) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isErr) Color(0xFFEF4444) else EmeraldAccent)
                        .clickable { viewModel.clearToast() }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("editor_toast")
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Modal Sheets
    if (showAudioSheet) {
        AudioMixSheet(
            originalVolume = settings.originalVolume,
            dubVolume = settings.dubVolume,
            dubEnabled = settings.dubEnabled,
            fitIndex = settings.fitIndex,
            onDismiss = { showAudioSheet = false },
            onUpdateMix = { orig, dub, en ->
                viewModel.settingsRepository.updateMix(orig, dub, en)
            },
            onUpdateFitIndex = {
                viewModel.settingsRepository.updateFitIndex(it)
            }
        )
    }

    if (showVoiceSheet) {
        VoiceSelectionSheet(
            femaleVoice = settings.femaleVoice,
            maleVoice = settings.maleVoice,
            onDismiss = { showVoiceSheet = false },
            onSelectFemaleVoice = {
                viewModel.settingsRepository.updateSettings(
                    apiKey = settings.apiKey,
                    groqApiKey = settings.groqApiKey,
                    sealionApiKey = settings.sealionApiKey,
                    transcriptionEngine = settings.transcriptionEngine,
                    translationEngine = settings.translationEngine,
                    customApiName = settings.customApiName,
                    customApiUrl = settings.customApiUrl,
                    customApiKey = settings.customApiKey,
                    customApiModel = settings.customApiModel,
                    ttsServerUrl = settings.ttsServerUrl,
                    language = settings.language,
                    femaleVoice = it,
                    maleVoice = settings.maleVoice,
                    style = settings.style
                )
            },
            onSelectMaleVoice = {
                viewModel.settingsRepository.updateSettings(
                    apiKey = settings.apiKey,
                    groqApiKey = settings.groqApiKey,
                    sealionApiKey = settings.sealionApiKey,
                    transcriptionEngine = settings.transcriptionEngine,
                    translationEngine = settings.translationEngine,
                    customApiName = settings.customApiName,
                    customApiUrl = settings.customApiUrl,
                    customApiKey = settings.customApiKey,
                    customApiModel = settings.customApiModel,
                    ttsServerUrl = settings.ttsServerUrl,
                    language = settings.language,
                    femaleVoice = settings.femaleVoice,
                    maleVoice = it,
                    style = settings.style
                )
            }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = settings,
            onDismiss = { showSettingsSheet = false },
            onSaveSettings = { updated ->
                viewModel.settingsRepository.saveSettings(updated)
            },
            onClearCache = {
                viewModel.clearCache()
            }
        )
    }

    if (editingSegment != null) {
        EditSegmentSheet(
            segment = editingSegment,
            currentTimeSec = currentTime,
            onDismiss = { editingSegment = null },
            onSave = { updated, shiftSubsequent ->
                viewModel.updateSegmentWithShift(updated, shiftSubsequent)
                editingSegment = null
            },
            onRegenerateVoice = { id ->
                viewModel.generateVoiceForSingleSegment(id)
                editingSegment = null
            },
            onDelete = { id ->
                viewModel.deleteSegment(id)
                editingSegment = null
            }
        )
    }

    if (showShiftTimelineDialog) {
        ShiftTimelineDialog(
            currentTimeSec = currentTime,
            firstSegmentStart = segments.firstOrNull()?.start,
            onDismiss = { showShiftTimelineDialog = false },
            onApplyShift = { offset ->
                viewModel.shiftAllSegments(offset)
            }
        )
    }

    if (speedAdjustSegment != null) {
        val seg = speedAdjustSegment!!
        val spoken = seg.translation?.takeIf { it.isNotBlank() } ?: seg.text
        val detected = SettingsRepository.calculateAutoSpeed(spoken, seg.end - seg.start)
        SpeedAdjustSheet(
            segment = seg,
            detectedSpeed = detected,
            onDismiss = { speedAdjustSegment = null },
            onSaveSpeed = { newSpeed, regen ->
                viewModel.updateSegmentSpeed(seg.id, newSpeed, regen)
            }
        )
    }

    ExportDialog(
        state = exportState,
        blurEnabled = blurSettings.enabled,
        onCancel = { viewModel.cancelExport() },
        onDismiss = { viewModel.dismissExportResult() }
    )

    if (showExportSheet) {
        ExportSheet(
            segmentCount = segments.size,
            translatedCount = translatedCount,
            voicedCount = voicedCount,
            hasVideo = !project?.videoPath.isNullOrBlank(),
            blurEnabled = blurSettings.enabled,
            isMuted = isMuted,
            onDismiss = { showExportSheet = false },
            onExportVideo = {
                showExportSheet = false
                viewModel.startExport(ExportKind.VIDEO)
            },
            onExportSrt = {
                showExportSheet = false
                shareText(context, viewModel.exportSrt(false), "${project?.name ?: "dubbed"}.srt")
            },
            onExportVtt = {
                showExportSheet = false
                shareText(context, viewModel.exportVtt(), "${project?.name ?: "dubbed"}.vtt")
            },
            onExportOriginalSrt = {
                showExportSheet = false
                shareText(context, viewModel.exportSrt(true), "${project?.name ?: "original"}_original.srt")
            },
            onExportWavDub = {
                showExportSheet = false
                viewModel.startExport(ExportKind.AUDIO)
            }
        )
    }
}

@Composable
private fun SegmentRowItem(
    segment: SegmentEntity,
    isActive: Boolean,
    isSelected: Boolean,
    isBusy: Boolean,
    isPlayingThisSegment: Boolean,
    voiceIssue: VoiceIssue?,
    onVoiceIssueClick: () -> Unit,
    onSelect: () -> Unit,
    onRowClick: () -> Unit,
    onGenderToggle: () -> Unit,
    onEditClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val isMale = segment.gender.equals("male", ignoreCase = true)
    val hasAudio = !segment.audioPath.isNullOrBlank()
    val spokenText = segment.translation?.takeIf { it.isNotBlank() } ?: segment.text

    // Speed calculation: custom speed if set manually, or auto detected speed
    val detectedSpeed = SettingsRepository.calculateAutoSpeed(spokenText, segment.end - segment.start)
    val effectiveSpeed = segment.customSpeed ?: detectedSpeed
    val isCustomSpeed = segment.customSpeed != null
    val speedHighlighted = isCustomSpeed || detectedSpeed >= 1.4f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) DarkAccent.copy(alpha = 0.16f) else DarkBg)
            .clickable(onClick = onRowClick)
            .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 7.dp)
            .testTag("segment_row_${segment.id}"),
        verticalAlignment = Alignment.Top
    ) {
        // Active line marker (always takes its width so rows don't shift)
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(width = 3.dp, height = 34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isActive) DarkAccent else Color.Transparent)
        )
        Spacer(modifier = Modifier.width(6.dp))

        // Selection checkbox
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (isSelected) DarkAccent else DarkCard)
                .border(1.dp, if (isSelected) DarkAccent else DarkLine, RoundedCornerShape(5.dp))
                .clickable(onClick = onSelect),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = "Select", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Metadata row: index, gender, time, speed, edit, play
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${segment.idx}",
                    color = if (isActive) DarkFg else DarkMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isActive) DarkAccent.copy(alpha = 0.35f) else DarkCard2)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )

                Spacer(modifier = Modifier.width(5.dp))

                // Gender toggle chip
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isMale) SkyAccentBg else FuchsiaAccentBg)
                        .clickable(onClick = onGenderToggle)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isMale) Icons.Default.Male else Icons.Default.Female,
                        contentDescription = if (isMale) "Male" else "Female",
                        tint = if (isMale) SkyAccent else FuchsiaAccent,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = if (isMale) "Male" else "Female",
                        color = if (isMale) SkyAccent else FuchsiaAccent,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "${SubtitleParser.formatTimeDisplay(segment.start)} → ${SubtitleParser.formatTimeDisplay(segment.end)}",
                    color = DarkMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Reading speed badge: tap to adjust
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                isCustomSpeed -> Color(0x33F59E0B)
                                detectedSpeed >= 1.4f -> Color(0x22F59E0B)
                                else -> DarkCard2
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                isCustomSpeed -> Color(0xFFF59E0B)
                                detectedSpeed >= 1.4f -> Color(0x80F59E0B)
                                else -> DarkLine
                            },
                            RoundedCornerShape(5.dp)
                        )
                        .clickable(onClick = onSpeedClick)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .testTag("speed_badge_${segment.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Speed",
                        tint = if (speedHighlighted) Color(0xFFF59E0B) else DarkMuted,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "%.1fx".format(effectiveSpeed) + (if (isCustomSpeed) "★" else ""),
                        color = if (speedHighlighted) Color(0xFFF59E0B) else DarkMuted,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Edit text button
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onEditClick)
                        .testTag("edit_button_${segment.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit segment",
                        tint = DarkMuted,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // This line couldn't be voiced: tap to see why
                if (voiceIssue != null) {
                    val issueColor = if (voiceIssue.skipped) Color(0xFFF59E0B) else Color(0xFFEF4444)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onVoiceIssueClick)
                            .testTag("voice_issue_${segment.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = if (voiceIssue.skipped) "No words to read" else "Voice failed",
                            tint = issueColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Play / generate the AI voice of this line
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isPlayingThisSegment -> EmeraldAccent
                                hasAudio -> EmeraldAccent.copy(alpha = 0.2f)
                                else -> DarkCard2
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                isPlayingThisSegment -> EmeraldAccent
                                hasAudio -> EmeraldAccent.copy(alpha = 0.7f)
                                else -> DarkLine
                            },
                            CircleShape
                        )
                        .clickable(enabled = !isBusy, onClick = onPlayClick)
                        .testTag("play_segment_${segment.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            color = if (hasAudio) EmeraldAccent else DarkAccent,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlayingThisSegment) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlayingThisSegment) "Pause dub" else if (hasAudio) "Play dub" else "Generate & Play TTS",
                            tint = when {
                                isPlayingThisSegment -> Color.Black
                                hasAudio -> EmeraldAccent
                                else -> DarkFg
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Translated text (prominent)
            Text(
                text = segment.translation ?: segment.text,
                color = if (!segment.translation.isNullOrBlank()) DarkFg else DarkFg.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            // Original text (if translation exists)
            if (!segment.translation.isNullOrBlank()) {
                Text(
                    text = segment.text,
                    color = DarkMuted,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

private fun shareText(context: android.content.Context, content: String, title: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, content)
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(sendIntent, title))
}
