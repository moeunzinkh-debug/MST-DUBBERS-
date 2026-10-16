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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SettingsRepository
import com.example.data.db.SegmentEntity
import com.example.ui.DubberViewModel
import com.example.ui.components.AudioMixSheet
import com.example.ui.components.EditSegmentSheet
import com.example.ui.components.ExportSheet
import com.example.ui.components.SettingsSheet
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

    var showMenu by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<SegmentEntity?>(null) }
    var speedAdjustSegment by remember { mutableStateOf<SegmentEntity?>(null) }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(3000)
            viewModel.clearToast()
        }
    }

    val activeSegment = remember(segments, currentTime) {
        segments.find { currentTime >= it.start && currentTime < it.end }
    }

    LaunchedEffect(activeSegment, isPlaying) {
        if (isPlaying && activeSegment != null) {
            val idx = segments.indexOfFirst { it.id == activeSegment.id }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
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
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
                IconButton(onClick = { showAudioSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Audio Mix",
                        tint = DarkFg
                    )
                }

                // Voice Personas Sheet Button
                IconButton(onClick = { showVoiceSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = "Voices",
                        tint = DarkFg
                    )
                }

                // Overflow Menu
                Box {
                    IconButton(onClick = { showMenu = !showMenu }) {
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
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Button 1: Translate with AI
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
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
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Translate",
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "Translate AI",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = translatingProgress?.let { "${it.done}/${it.total}" } ?: "$translatedCount/${segments.size}",
                            color = Color.Black.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Button 2: Voice with AI (TTS)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(VoiceButtonBg)
                        .clickable(enabled = voicingProgress == null && segments.isNotEmpty()) {
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
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Voice AI",
                                tint = VoiceButtonFg,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "Edge TTS Dub",
                            color = VoiceButtonFg,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = voicingProgress?.let { "${it.done}/${it.total}" }
                                ?: if (selectedIds.isNotEmpty()) "${selectedIds.size} selected" else "$voicedCount/${segments.size}",
                            color = VoiceButtonFg.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Button 3: Export
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
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
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Export",
                            color = DarkFg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = "Dub & Subs",
                            color = DarkMuted,
                            fontSize = 11.sp
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
            VideoPlayerView(
                videoPath = project?.videoPath ?: "",
                isPlaying = isPlaying,
                onPlayPauseToggle = { viewModel.setPlaying(it) },
                currentTimeSec = currentTime,
                onSeek = { viewModel.seekTo(it) },
                durationSec = duration,
                onDurationKnown = { viewModel.setDuration(it) },
                onTimeUpdate = { viewModel.setTime(it) },
                isMuted = isMuted,
                onMuteToggle = { viewModel.toggleMute() },
                activeSubtitleText = overlayText,
                showSubtitles = settings.showSubtitles,
                isDubPlaying = isDubPlaying,
                originalVolume = settings.originalVolume,
                isTranscribing = isTranscribing
            )

            // Stats & Auto-Fit Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg2)
                    .border(width = 1.dp, color = DarkLine)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (allSelected) DarkAccent else DarkCard)
                        .border(1.dp, if (allSelected) DarkAccent else DarkLine, RoundedCornerShape(6.dp))
                        .clickable { viewModel.toggleSelectAll() },
                    contentAlignment = Alignment.Center
                ) {
                    if (allSelected) {
                        Icon(Icons.Default.Check, contentDescription = "All", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.ClosedCaption, contentDescription = "Subs", tint = DarkMuted, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${segments.size}", color = DarkFg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Translate, contentDescription = "Tr", tint = SkyAccent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("$translatedCount", color = SkyAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.GraphicEq, contentDescription = "Voiced", tint = EmeraldAccent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("$voicedCount", color = EmeraldAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "Auto-Fit ${fitMode?.label ?: "Off"}",
                        color = DarkMuted,
                        fontSize = 12.sp,
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
                            onSelect = { viewModel.toggleSegmentSelection(seg.id) },
                            onRowClick = { viewModel.seekTo(seg.start + 0.05) },
                            onGenderToggle = {
                                val nextGender = if (seg.gender.equals("male", ignoreCase = true)) "female" else "male"
                                viewModel.updateSegment(seg.copy(gender = nextGender))
                            },
                            onEditClick = { editingSegment = seg },
                            onSpeedClick = { speedAdjustSegment = seg },
                            onPlayClick = { viewModel.previewOrGenerateAudio(seg) }
                        )
                        HorizontalDivider(color = DarkLine, thickness = 1.dp)
                    }
                }
            }
        }

        // Floating Toast Notification
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.padding(16.dp)
        ) {
            toastMessage?.let { (msg, isErr) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isErr) Color(0xFFEF4444) else EmeraldAccent)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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
            onDismiss = { editingSegment = null },
            onSave = { updated ->
                viewModel.updateSegment(updated)
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

    if (showExportSheet) {
        ExportSheet(
            segmentCount = segments.size,
            translatedCount = translatedCount,
            voicedCount = voicedCount,
            onDismiss = { showExportSheet = false },
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
                val wavFile = viewModel.exportCombinedWavDub()
                if (wavFile != null) {
                    shareFile(context, wavFile, "audio/wav")
                } else {
                    viewModel.showToast("No voiced segments to export yet. Generate AI voice first!", true)
                }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) DarkAccent.copy(alpha = 0.16f) else DarkBg)
            .clickable(onClick = onRowClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("segment_row_${segment.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Left active vertical strip
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 48.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DarkAccent)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            // Selection checkbox
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) DarkAccent else DarkCard)
                    .border(1.dp, if (isSelected) DarkAccent else DarkLine, RoundedCornerShape(6.dp))
                    .clickable(onClick = onSelect),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = "Select", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Top metadata row: Index, Gender Badge, Timestamps, Reading Speed (clickable), Edit, Play Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Index badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isActive) DarkAccent.copy(alpha = 0.35f) else DarkCard2)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${segment.idx}",
                            color = if (isActive) DarkFg else DarkMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Gender toggle chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isMale) SkyAccentBg else FuchsiaAccentBg)
                            .clickable(onClick = onGenderToggle)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isMale) Icons.Default.Male else Icons.Default.Female,
                                contentDescription = if (isMale) "Male" else "Female",
                                tint = if (isMale) SkyAccent else FuchsiaAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isMale) "Male" else "Female",
                                color = if (isMale) SkyAccent else FuchsiaAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Timestamps
                    Text(
                        text = "${SubtitleParser.formatTimeDisplay(segment.start)} → ${SubtitleParser.formatTimeDisplay(segment.end)}",
                        color = DarkMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Reading speed badge: clickable to manually adjust speed
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
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
                                RoundedCornerShape(6.dp)
                            )
                            .clickable(onClick = onSpeedClick)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("speed_badge_${segment.id}")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Speed",
                                tint = if (isCustomSpeed || detectedSpeed >= 1.4f) Color(0xFFF59E0B) else DarkMuted,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "%.1fx".format(effectiveSpeed) + (if (isCustomSpeed) "★" else ""),
                                color = if (isCustomSpeed || detectedSpeed >= 1.4f) Color(0xFFF59E0B) else DarkMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Edit text button
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("edit_button_${segment.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit segment",
                            tint = DarkMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // Play button (Replaced waveform icon as requested with arrows)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
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
                                modifier = Modifier.size(18.dp),
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
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Translated text (prominent)
                Text(
                    text = segment.translation ?: segment.text,
                    color = if (!segment.translation.isNullOrBlank()) DarkFg else DarkFg.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )

                // Original text (if translation exists)
                if (!segment.translation.isNullOrBlank()) {
                    Text(
                        text = segment.text,
                        color = DarkMuted,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
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

private fun shareFile(context: android.content.Context, file: java.io.File, mime: String) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share Dubbed Audio"))
    } catch (e: Exception) {
        shareText(context, file.name, "Share Audio")
    }
}
