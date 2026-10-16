package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.ProjectWithStats
import com.example.ui.DubberViewModel
import com.example.ui.components.HeroNetwork
import com.example.ui.components.SettingsSheet
import com.example.ui.theme.DarkAccent
import com.example.ui.theme.DarkAccentVariant
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCard2
import com.example.ui.theme.DarkFg
import com.example.ui.theme.DarkLine
import com.example.ui.theme.DarkMuted
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.SkyAccent
import com.example.util.SubtitleParser
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: DubberViewModel,
    onNavigateToEditor: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val projects by viewModel.allProjects.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf("") }
    var selectedSubContent by remember { mutableStateOf<String?>(null) }
    var selectedSubName by remember { mutableStateOf("") }
    var transcribeWithAI by remember { mutableStateOf(false) }

    var isCreatingProject by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSubChoiceSheet by remember { mutableStateOf(false) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            selectedVideoName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected Video"
        }
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = BufferedReader(InputStreamReader(stream)).readText()
                    selectedSubContent = content
                    selectedSubName = uri.lastPathSegment?.substringAfterLast('/') ?: "Subtitles File"
                    transcribeWithAI = false
                }
            } catch (e: Exception) {
                viewModel.showToast("Cannot read subtitle file: ${e.message}", true)
            }
        }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MST DUBBERS FREE",
                    color = DarkMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.5.sp
                )

                IconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(DarkCard2)
                        .border(1.dp, DarkLine, CircleShape)
                        .testTag("home_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = DarkFg
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp)
        ) {
            item {
                HeroNetwork()
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "AI Video Dubbing Studio",
                        color = DarkFg,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Automated dialogue transcription (Gemini & Groq Whisper Large v3), smart translation (Gemini, SeaLion & Custom), and Edge TTS voice dubbing.",
                        color = DarkMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp)
                    )
                }
            }

            // Step 1: Video Selection Card
            item {
                val hasVideo = selectedVideoUri != null
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(DarkCard)
                        .border(
                            1.dp,
                            if (hasVideo) DarkAccent.copy(alpha = 0.7f) else DarkLine,
                            RoundedCornerShape(22.dp)
                        )
                        .clickable { videoPickerLauncher.launch("video/*") }
                        .padding(16.dp)
                        .testTag("step_1_video_picker")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (hasVideo) DarkAccent.copy(alpha = 0.2f) else DarkCard2),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hasVideo) Icons.Default.Check else Icons.Default.Movie,
                                contentDescription = "Video step",
                                tint = if (hasVideo) DarkAccent else DarkMuted,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("STEP 1", color = DarkMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Choose Video", color = DarkFg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = if (hasVideo) selectedVideoName else "Select video file (.mp4, .webm, .mkv)",
                                color = if (hasVideo) DarkFg else DarkMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Select",
                            tint = DarkMuted
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Step 2: Subtitle / AI Transcribe Card
            item {
                val hasSubs = selectedSubContent != null || transcribeWithAI
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(DarkCard)
                        .border(
                            1.dp,
                            if (hasSubs) DarkAccent.copy(alpha = 0.7f) else DarkLine,
                            RoundedCornerShape(22.dp)
                        )
                        .clickable { showSubChoiceSheet = true }
                        .padding(16.dp)
                        .testTag("step_2_subtitles_picker")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (hasSubs) DarkAccent.copy(alpha = 0.2f) else DarkCard2),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hasSubs) Icons.Default.Check else Icons.Default.ClosedCaption,
                                contentDescription = "Subtitles step",
                                tint = if (hasSubs) DarkAccent else DarkMuted,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("STEP 2", color = DarkMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Subtitles", color = DarkFg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(DarkCard2)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Optional", color = DarkMuted, fontSize = 10.sp)
                                }
                            }
                            Text(
                                text = when {
                                    transcribeWithAI -> "Auto-Transcribe (${if (settings.transcriptionEngine == "groq_whisper") "Groq Whisper Large v3" else "Gemini"}) on start"
                                    selectedSubContent != null -> selectedSubName
                                    else -> "Import .srt / .vtt or Transcribe with AI"
                                },
                                color = if (hasSubs) DarkFg else DarkMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Select",
                            tint = DarkMuted
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            selectedVideoUri = null
                            selectedVideoName = "Sample Clip (MST Dubbers.mp4)"
                            selectedSubContent = """
                                1
                                00:00:01,000 --> 00:00:03,500
                                Welcome to MST DUBBERS FREE studio.

                                2
                                00:00:04,000 --> 00:00:07,500
                                Professional film dubbing with Edge TTS and AI translation.
                            """.trimIndent()
                            selectedSubName = "demo_subtitles.srt"
                            transcribeWithAI = false
                        }
                    ) {
                        Text("Use Built-in Sample Project", color = DarkAccent, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Start / Create Project Button
            item {
                val canStart = selectedVideoUri != null || selectedVideoName.isNotBlank()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (canStart) {
                                Brush.horizontalGradient(listOf(DarkAccent, DarkAccentVariant))
                            } else {
                                Brush.horizontalGradient(listOf(DarkCard2, DarkCard2))
                            }
                        )
                        .clickable(enabled = canStart && !isCreatingProject) {
                            isCreatingProject = true
                            scope.launch {
                                try {
                                    val projectName = selectedVideoName.substringBeforeLast('.')
                                        .ifBlank { "Project #${projects.size + 1}" }
                                    val id = viewModel.repository.createProject(
                                        name = projectName,
                                        videoUri = selectedVideoUri,
                                        subtitleContent = selectedSubContent
                                    )
                                    if (transcribeWithAI) {
                                        viewModel.loadProject(id)
                                        viewModel.startTranscribeAI()
                                    }
                                    onNavigateToEditor(id)
                                } catch (e: Exception) {
                                    viewModel.showToast("Failed to create project: ${e.message}", true)
                                } finally {
                                    isCreatingProject = false
                                }
                            }
                        }
                        .testTag("start_dubbing_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCreatingProject) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Setting up studio...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Enter",
                                tint = if (canStart) Color.White else DarkMuted
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Enter Dubbing Studio",
                                color = if (canStart) Color.White else DarkMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(28.dp))
            }

            // Divider
            item {
                HorizontalDivider(color = DarkLine, thickness = 1.dp)
                Spacer(modifier = Modifier.height(18.dp))
            }

            // Recent Projects Section Header
            item {
                Text(
                    text = "RECENT PROJECTS",
                    color = DarkMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            if (projects.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, DarkLine, RoundedCornerShape(18.dp))
                            .padding(vertical = 36.dp, horizontal = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No recent projects yet. Pick a video above to begin dubbing.",
                            color = DarkMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(30.dp))
                }
            } else {
                items(projects, key = { it.id }) { proj ->
                    ProjectListItem(
                        project = proj,
                        onClick = { onNavigateToEditor(proj.id) },
                        onDelete = { viewModel.deleteProjectById(proj.id) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }
    }

    // Subtitle Choice Sheet
    if (showSubChoiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSubChoiceSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DarkBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "Subtitles Source",
                    color = DarkFg,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Option 1: File import
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkCard)
                        .border(1.dp, DarkLine, RoundedCornerShape(16.dp))
                        .clickable {
                            showSubChoiceSheet = false
                            subtitlePickerLauncher.launch("*/*")
                        }
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkCard2),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.ClosedCaption, contentDescription = "Subtitles", tint = DarkMuted)
                        }
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text("Upload .SRT or .VTT file", color = DarkFg, fontWeight = FontWeight.SemiBold)
                            Text("Instant accurate subtitle timestamps", color = DarkMuted, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Option 2: AI Transcribe (shows Gemini or Groq Whisper depending on setting)
                val engineLabel = if (settings.transcriptionEngine == "groq_whisper") "Groq Whisper Large v3" else "Gemini AI"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkCard)
                        .border(1.dp, DarkLine, RoundedCornerShape(16.dp))
                        .clickable {
                            transcribeWithAI = true
                            selectedSubContent = null
                            selectedSubName = ""
                            showSubChoiceSheet = false
                        }
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkAccent.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.GraphicEq, contentDescription = "AI", tint = DarkAccent)
                        }
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text("Transcribe with $engineLabel", color = DarkFg, fontWeight = FontWeight.SemiBold)
                            Text("Auto-detect speech, timestamps & gender", color = DarkMuted, fontSize = 12.sp)
                        }
                    }
                }

                if (selectedSubContent != null || transcribeWithAI) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            selectedSubContent = null
                            selectedSubName = ""
                            transcribeWithAI = false
                            showSubChoiceSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Subtitles Selection", color = Color.Red.copy(alpha = 0.8f))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Settings Modal Sheet
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
}

@Composable
private fun ProjectListItem(
    project: ProjectWithStats,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DarkCard)
            .border(1.dp, DarkLine, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag("project_item_${project.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkCard2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = "Project icon",
                    tint = DarkMuted,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = project.name,
                    color = DarkFg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = SubtitleParser.formatTimeDisplay(project.duration),
                        color = DarkMuted,
                        fontSize = 12.sp
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.ClosedCaption, contentDescription = "Subs", tint = DarkMuted, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("${project.segmentCount}", color = DarkMuted, fontSize = 12.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Translate, contentDescription = "Tr", tint = SkyAccent, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("${project.translatedCount}", color = SkyAccent, fontSize = 12.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.GraphicEq, contentDescription = "Voiced", tint = EmeraldAccent, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("${project.voicedCount}", color = EmeraldAccent, fontSize = 12.sp)
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete project",
                    tint = DarkMuted.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
