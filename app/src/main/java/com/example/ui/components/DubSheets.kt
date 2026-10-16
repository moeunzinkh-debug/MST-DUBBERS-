package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppSettings
import com.example.data.SettingsRepository
import com.example.data.api.GeminiModels
import com.example.data.db.SegmentEntity
import com.example.ui.theme.DarkAccent
import com.example.ui.theme.DarkBg2
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCard2
import com.example.ui.theme.DarkFg
import com.example.ui.theme.DarkLine
import com.example.ui.theme.DarkMuted
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldAccentBg
import com.example.ui.theme.FuchsiaAccent
import com.example.ui.theme.FuchsiaAccentBg
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.SkyAccentBg
import com.example.ui.theme.VoiceButtonBg
import com.example.ui.theme.VoiceButtonFg

// --- Settings Dialog Sheet ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSaveSettings: (AppSettings) -> Unit,
    onClearCache: () -> Unit
) {
    var apiKeyText by remember { mutableStateOf(settings.apiKey) }
    var selectedGeminiModel by remember { mutableStateOf(GeminiModels.resolve(settings.geminiModel)) }
    var groqApiKeyText by remember { mutableStateOf(settings.groqApiKey) }
    var sealionApiKeyText by remember { mutableStateOf(settings.sealionApiKey) }
    var sealionModelText by remember { mutableStateOf(settings.sealionModel) }
    var sealionUrlText by remember { mutableStateOf(settings.sealionUrl) }

    var transcriptionEngine by remember { mutableStateOf(settings.transcriptionEngine) }
    var translationEngine by remember { mutableStateOf(settings.translationEngine) }

    var customNameText by remember { mutableStateOf(settings.customApiName) }
    var customUrlText by remember { mutableStateOf(settings.customApiUrl) }
    var customKeyText by remember { mutableStateOf(settings.customApiKey) }
    var customModelText by remember { mutableStateOf(settings.customApiModel) }

    var ttsServerUrlText by remember { mutableStateOf(settings.ttsServerUrl) }
    var selectedLanguage by remember { mutableStateOf(settings.language) }
    var selectedFemaleVoice by remember { mutableStateOf(settings.femaleVoice) }
    var selectedMaleVoice by remember { mutableStateOf(settings.maleVoice) }
    var styleText by remember { mutableStateOf(settings.style) }

    var showKey by remember { mutableStateOf(false) }
    var langDropdownExpanded by remember { mutableStateOf(false) }
    var geminiModelDropdownExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkBg2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings & AI Models",
                    color = DarkFg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = DarkMuted)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Transcription Engine Selector
            Text("Transcription Engine", color = SkyAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("gemini" to GeminiModels.labelFor(selectedGeminiModel), "groq_whisper" to "Groq Whisper Large v3").forEach { (key, label) ->
                    val isSel = transcriptionEngine == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) DarkAccent else DarkCard)
                            .clickable { transcriptionEngine = key }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (isSel) Color.White else DarkMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Translation Engine Selector (Gemini / SeaLion / Custom Model)
            Text("Translation Engine (ការបកប្រែ)", color = SkyAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("gemini" to "Gemini", "sealion" to "SeaLion", "custom" to "Custom API").forEach { (key, label) ->
                    val isSel = translationEngine == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) DarkAccent else DarkCard)
                            .clickable { translationEngine = key }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (isSel) Color.White else DarkMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2b. Gemini Model drop-down (Gemini 3.8 Flash / Gemini 3.6 Flash)
            Text("Gemini AI Model (ម៉ូដែល Gemini)", color = SkyAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            ExposedDropdownMenuBox(
                expanded = geminiModelDropdownExpanded,
                onExpandedChange = { geminiModelDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = GeminiModels.labelFor(selectedGeminiModel),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = SkyAccent)
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = geminiModelDropdownExpanded) },
                    supportingText = {
                        Text("Used for Gemini transcription & translation", color = DarkMuted, fontSize = 11.sp)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                        .testTag("gemini_model_dropdown"),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = geminiModelDropdownExpanded,
                    onDismissRequest = { geminiModelDropdownExpanded = false },
                    modifier = Modifier.background(DarkCard)
                ) {
                    GeminiModels.OPTIONS.forEach { option ->
                        val isSelected = option.id == selectedGeminiModel
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = option.label,
                                        color = if (isSelected) SkyAccent else DarkFg,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(text = option.description, color = DarkMuted, fontSize = 12.sp)
                                }
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Selected", tint = SkyAccent)
                                }
                            },
                            onClick = {
                                selectedGeminiModel = option.id
                                geminiModelDropdownExpanded = false
                            },
                            modifier = Modifier.testTag("gemini_model_option_${option.id}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. API Keys Section
            Text("API Keys Configuration", color = DarkFg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // Gemini API Key
            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it },
                label = { Text("Gemini API Key") },
                modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
                placeholder = { Text("AIza...", color = DarkMuted) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = DarkMuted)
                    }
                },
                colors = defaultTextFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Groq API Key
            OutlinedTextField(
                value = groqApiKeyText,
                onValueChange = { groqApiKeyText = it },
                label = { Text("Groq API Key (for Whisper Large v3)") },
                modifier = Modifier.fillMaxWidth().testTag("groq_key_input"),
                placeholder = { Text("gsk_...", color = DarkMuted) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                colors = defaultTextFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // SeaLion API Key
            OutlinedTextField(
                value = sealionApiKeyText,
                onValueChange = { sealionApiKeyText = it },
                label = { Text("SeaLion API Key") },
                modifier = Modifier.fillMaxWidth().testTag("sealion_key_input"),
                placeholder = { Text("SeaLion token...", color = DarkMuted) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                colors = defaultTextFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )

            if (translationEngine == "sealion") {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = sealionModelText,
                    onValueChange = { sealionModelText = it },
                    label = { Text("SEA-LION Model ID") },
                    placeholder = { Text("aisingapore/Qwen-SEA-LION-v4.5-27B-IT") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = sealionUrlText,
                    onValueChange = { sealionUrlText = it },
                    label = { Text("SEA-LION Endpoint URL") },
                    placeholder = { Text("https://api.together.xyz/v1/chat/completions") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            // If Custom Model chosen, show Name, Url, Key, Model
            if (translationEngine == "custom") {
                Spacer(modifier = Modifier.height(14.dp))
                Text("Custom API Model Configuration", color = EmeraldAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = customNameText,
                    onValueChange = { customNameText = it },
                    label = { Text("Custom API Name") },
                    placeholder = { Text("My Custom Provider") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customUrlText,
                    onValueChange = { customUrlText = it },
                    label = { Text("Custom API URL (Endpoint)") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customKeyText,
                    onValueChange = { customKeyText = it },
                    label = { Text("Custom API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customModelText,
                    onValueChange = { customModelText = it },
                    label = { Text("Custom Model ID") },
                    placeholder = { Text("e.g. gpt-4o-mini, llama-3.3-70b") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Edge TTS Server URL
            Text("Edge TTS Voice Server (Free)", color = DarkFg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = ttsServerUrlText,
                onValueChange = { ttsServerUrlText = it },
                label = { Text("TTS Server Endpoint") },
                placeholder = { Text("https://takumi0002-tts-kh.hf.space") },
                modifier = Modifier.fillMaxWidth().testTag("tts_server_url_input"),
                colors = defaultTextFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Target Language Dropdown
            Text("Target Dubbing Language", color = DarkMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            ExposedDropdownMenuBox(
                expanded = langDropdownExpanded,
                onExpandedChange = { langDropdownExpanded = !langDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = SettingsRepository.LANGUAGES.find { it.code == selectedLanguage }?.label ?: selectedLanguage,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langDropdownExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                    colors = defaultTextFieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = langDropdownExpanded,
                    onDismissRequest = { langDropdownExpanded = false },
                    modifier = Modifier.background(DarkCard)
                ) {
                    SettingsRepository.LANGUAGES.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.label, color = DarkFg) },
                            onClick = {
                                selectedLanguage = lang.code
                                langDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Clear Cache Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkCard)
                    .border(1.dp, DarkLine, RoundedCornerShape(12.dp))
                    .clickable { onClearCache() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.CleaningServices, contentDescription = "Clear cache", tint = Color(0xFFF59E0B))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Clear Cache", color = DarkFg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Free up stored audio dubs & temp files", color = DarkMuted, fontSize = 12.sp)
                    }
                }
                Text("Clean", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. About Section (with exact Khmer text)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkCard)
                    .border(1.dp, DarkLine, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = "About", tint = DarkAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("About MST DUBBERS FREE", color = DarkFg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = SettingsRepository.ABOUT_TEXT,
                        color = DarkMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Save Settings Button
            Button(
                onClick = {
                    onSaveSettings(
                        settings.copy(
                            apiKey = apiKeyText,
                            geminiModel = selectedGeminiModel,
                            groqApiKey = groqApiKeyText,
                            sealionApiKey = sealionApiKeyText,
                            sealionModel = sealionModelText,
                            sealionUrl = sealionUrlText,
                            transcriptionEngine = transcriptionEngine,
                            translationEngine = translationEngine,
                            customApiName = customNameText,
                            customApiUrl = customUrlText,
                            customApiKey = customKeyText,
                            customApiModel = customModelText,
                            ttsServerUrl = ttsServerUrlText,
                            language = selectedLanguage,
                            femaleVoice = selectedFemaleVoice,
                            maleVoice = selectedMaleVoice,
                            style = styleText
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_settings_button"),
                colors = ButtonDefaults.buttonColors(containerColor = DarkAccent),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Save Settings", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun defaultTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = DarkAccent,
    unfocusedBorderColor = DarkLine,
    focusedTextColor = DarkFg,
    unfocusedTextColor = DarkFg,
    focusedContainerColor = DarkCard,
    unfocusedContainerColor = DarkCard,
    focusedLabelColor = DarkAccent,
    unfocusedLabelColor = DarkMuted
)

// --- Voice Selection Sheet ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceSelectionSheet(
    femaleVoice: String,
    maleVoice: String,
    onDismiss: () -> Unit,
    onSelectFemaleVoice: (String) -> Unit,
    onSelectMaleVoice: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkBg2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edge TTS Voice Personas",
                    color = DarkFg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = DarkMuted)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Female voices
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Female, contentDescription = "Female", tint = FuchsiaAccent)
                Text(
                    text = "Female Voice (${femaleVoice})",
                    color = FuchsiaAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsRepository.EDGE_FEMALE_VOICES.forEach { voice ->
                    val selected = voice == femaleVoice
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) DarkAccent else DarkCard)
                            .border(1.dp, if (selected) DarkAccent else DarkLine, RoundedCornerShape(10.dp))
                            .clickable { onSelectFemaleVoice(voice) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = voice,
                            color = if (selected) Color.White else DarkMuted,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Male voices
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Male, contentDescription = "Male", tint = SkyAccent)
                Text(
                    text = "Male Voice (${maleVoice})",
                    color = SkyAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsRepository.EDGE_MALE_VOICES.forEach { voice ->
                    val selected = voice == maleVoice
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) DarkAccent else DarkCard)
                            .border(1.dp, if (selected) DarkAccent else DarkLine, RoundedCornerShape(10.dp))
                            .clickable { onSelectMaleVoice(voice) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = voice,
                            color = if (selected) Color.White else DarkMuted,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkAccent),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// --- Audio Mix Sheet ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMixSheet(
    originalVolume: Float,
    dubVolume: Float,
    dubEnabled: Boolean,
    fitIndex: Int,
    onDismiss: () -> Unit,
    onUpdateMix: (origVol: Float, dubVol: Float, enabled: Boolean) -> Unit,
    onUpdateFitIndex: (Int) -> Unit
) {
    var origVol by remember { mutableFloatStateOf(originalVolume) }
    var dubVol by remember { mutableFloatStateOf(dubVolume) }
    var dubOn by remember { mutableStateOf(dubEnabled) }
    var currentFit by remember { mutableIntStateOf(fitIndex) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkBg2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio Mix & Timing",
                    color = DarkFg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = DarkMuted)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Original audio slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Original Video Volume", color = DarkFg, fontSize = 14.sp)
                Text("${(origVol * 100).toInt()}%", color = DarkMuted, fontSize = 14.sp)
            }
            Slider(
                value = origVol,
                onValueChange = {
                    origVol = it
                    onUpdateMix(origVol, dubVol, dubOn)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = DarkAccent, activeTrackColor = DarkAccent)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // AI Dub volume slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("AI Dubbed Audio Volume", color = DarkFg, fontSize = 14.sp)
                Text("${(dubVol * 100).toInt()}%", color = DarkMuted, fontSize = 14.sp)
            }
            Slider(
                value = dubVol,
                onValueChange = {
                    dubVol = it
                    onUpdateMix(origVol, dubVol, dubOn)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = DarkAccent, activeTrackColor = DarkAccent)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Enable dub toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkCard)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable AI Dub Track", color = DarkFg, fontSize = 15.sp)
                Switch(
                    checked = dubOn,
                    onCheckedChange = {
                        dubOn = it
                        onUpdateMix(origVol, dubVol, dubOn)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DarkAccent)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-Fit Speed multiplier
            Text(
                text = "Auto-Fit Speed Multiplier",
                color = DarkMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsRepository.FIT_MODES.forEachIndexed { index, mode ->
                    val selected = index == currentFit
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) DarkAccent else DarkCard)
                            .clickable {
                                currentFit = index
                                onUpdateFitIndex(index)
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            color = if (selected) Color.White else DarkMuted,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// --- Edit Segment Sheet ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSegmentSheet(
    segment: SegmentEntity?,
    onDismiss: () -> Unit,
    onSave: (SegmentEntity) -> Unit,
    onRegenerateVoice: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    if (segment == null) return

    var startText by remember(segment) { mutableStateOf(segment.start.toString()) }
    var endText by remember(segment) { mutableStateOf(segment.end.toString()) }
    var gender by remember(segment) { mutableStateOf(segment.gender) }
    var originalText by remember(segment) { mutableStateOf(segment.text) }
    var translationText by remember(segment) { mutableStateOf(segment.translation ?: "") }
    var customSpeedValue by remember(segment) { mutableStateOf(segment.customSpeed) }
    var isBusy by remember { mutableStateOf(false) }

    val autoSpeed = remember(segment, translationText, originalText, startText, endText) {
        val txt = translationText.ifBlank { originalText }
        val dur = ((endText.toDoubleOrNull() ?: segment.end) - (startText.toDoubleOrNull() ?: segment.start)).coerceAtLeast(0.3)
        SettingsRepository.calculateAutoSpeed(txt, dur)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkBg2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edit Segment #${segment.idx}",
                    color = DarkFg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = DarkMuted)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Timestamps
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Start (seconds)", color = DarkMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        singleLine = true,
                        colors = defaultTextFieldColors(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("End (seconds)", color = DarkMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        singleLine = true,
                        colors = defaultTextFieldColors(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Speaker gender
            Text("Speaker Gender", color = DarkMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val isFemale = gender.equals("female", ignoreCase = true)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isFemale) FuchsiaAccentBg else DarkCard)
                        .border(1.dp, if (isFemale) FuchsiaAccent else DarkLine, RoundedCornerShape(10.dp))
                        .clickable { gender = "female" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Female, contentDescription = "Female", tint = FuchsiaAccent)
                        Text("Female", color = if (isFemale) FuchsiaAccent else DarkMuted, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (!isFemale) SkyAccentBg else DarkCard)
                        .border(1.dp, if (!isFemale) SkyAccent else DarkLine, RoundedCornerShape(10.dp))
                        .clickable { gender = "male" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Male, contentDescription = "Male", tint = SkyAccent)
                        Text("Male", color = if (!isFemale) SkyAccent else DarkMuted, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Speed Section (Manual or Auto)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Speed, contentDescription = "Speed", tint = DarkAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reading Speed (ល្បឿនអាន)", color = DarkFg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = if (customSpeedValue != null) "%.2fx (Manual)".format(customSpeedValue) else "%.2fx (Auto)".format(autoSpeed),
                    color = if (customSpeedValue != null) Color(0xFFF59E0B) else SkyAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Speed presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(null to "Auto", 1.0f to "1.0x", 1.2f to "1.2x", 1.4f to "1.4x", 1.5f to "1.5x", 1.8f to "1.8x").forEach { (speedOpt, label) ->
                    val isSelected = (speedOpt == null && customSpeedValue == null) || (speedOpt != null && customSpeedValue == speedOpt)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) DarkAccent else DarkCard)
                            .border(1.dp, if (isSelected) DarkAccent else DarkLine, RoundedCornerShape(8.dp))
                            .clickable { customSpeedValue = speedOpt }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else DarkMuted,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Slider for manual speed
            val currentSlider = customSpeedValue ?: autoSpeed
            Slider(
                value = currentSlider.coerceIn(0.5f, 2.5f),
                onValueChange = {
                    val rounded = (Math.round(it * 20f) / 20f)
                    customSpeedValue = rounded
                },
                valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(thumbColor = DarkAccent, activeTrackColor = DarkAccent),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Original Text
            Text("Original Text", color = DarkMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = originalText,
                onValueChange = { originalText = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                colors = defaultTextFieldColors(),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Translated Text
            Text("Translated / Dubbed Text", color = DarkMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = translationText,
                onValueChange = { translationText = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                colors = defaultTextFieldColors(),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val s = startText.toDoubleOrNull() ?: segment.start
                        val e = endText.toDoubleOrNull() ?: segment.end
                        onSave(
                            segment.copy(
                                start = s,
                                end = maxOf(e, s + 0.1),
                                gender = gender,
                                customSpeed = customSpeedValue,
                                text = originalText,
                                translation = translationText.ifBlank { null }
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        isBusy = true
                        val s = startText.toDoubleOrNull() ?: segment.start
                        val e = endText.toDoubleOrNull() ?: segment.end
                        val updated = segment.copy(
                            start = s,
                            end = maxOf(e, s + 0.1),
                            gender = gender,
                            customSpeed = customSpeedValue,
                            text = originalText,
                            translation = translationText.ifBlank { null }
                        )
                        onSave(updated)
                        onRegenerateVoice(segment.id)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.3f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VoiceButtonBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.GraphicEq, contentDescription = "Voice", tint = VoiceButtonFg)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save & TTS", color = VoiceButtonFg, fontWeight = FontWeight.SemiBold)
                }

                IconButton(
                    onClick = {
                        onDelete(segment.id)
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Red.copy(alpha = 0.15f))
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// --- Speed Adjust Sheet (Quick manual speed picker from badge) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedAdjustSheet(
    segment: SegmentEntity,
    detectedSpeed: Float,
    onDismiss: () -> Unit,
    onSaveSpeed: (newSpeed: Float?, regenerateNow: Boolean) -> Unit
) {
    var selectedSpeed by remember(segment) { mutableStateOf(segment.customSpeed) }
    var regenerateNow by remember { mutableStateOf(!segment.audioPath.isNullOrBlank()) }
    val effectiveCurrent = selectedSpeed ?: detectedSpeed

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkBg2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Speed, contentDescription = "Speed", tint = Color(0xFFF59E0B), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Adjust Speed #${segment.idx}",
                        color = DarkFg,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = DarkMuted)
                }
            }

            Text(
                text = segment.translation ?: segment.text,
                color = DarkMuted,
                fontSize = 13.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            // Current speed badge & label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkCard)
                    .border(1.dp, DarkLine, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (selectedSpeed != null) "Manual Custom Speed" else "Auto Calculated Speed",
                        color = DarkMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "%.2fx".format(effectiveCurrent),
                        color = if (selectedSpeed != null) Color(0xFFF59E0B) else DarkAccent,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedSpeed == null) DarkAccent.copy(alpha = 0.2f) else DarkCard2)
                        .clickable { selectedSpeed = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Reset to Auto",
                        color = if (selectedSpeed == null) DarkAccent else DarkMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Presets
            Text("Speed Presets", color = DarkMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.5f, 1.8f, 2.0f).forEach { speedVal ->
                    val isSel = selectedSpeed == speedVal
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Color(0xFFF59E0B) else DarkCard)
                            .border(1.dp, if (isSel) Color(0xFFF59E0B) else DarkLine, RoundedCornerShape(8.dp))
                            .clickable { selectedSpeed = speedVal }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${speedVal}x",
                            color = if (isSel) Color.Black else DarkFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Continuous fine tuning slider
            Slider(
                value = effectiveCurrent.coerceIn(0.5f, 2.5f),
                onValueChange = {
                    selectedSpeed = (Math.round(it * 20f) / 20f)
                },
                valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFF59E0B),
                    activeTrackColor = Color(0xFFF59E0B)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Regenerate option if segment already voiced
            if (!segment.audioPath.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkCard)
                        .clickable { regenerateNow = !regenerateNow }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = regenerateNow,
                        onCheckedChange = { regenerateNow = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DarkAccent)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Regenerate Edge TTS voice immediately", color = DarkFg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Applies new speed to synthesized audio file", color = DarkMuted, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkCard2),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = DarkMuted, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = {
                        onSaveSpeed(selectedSpeed, regenerateNow)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.5f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Apply Speed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// --- Export Sheet ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    segmentCount: Int,
    translatedCount: Int,
    voicedCount: Int,
    onDismiss: () -> Unit,
    onExportSrt: () -> Unit,
    onExportVtt: () -> Unit,
    onExportOriginalSrt: () -> Unit,
    onExportWavDub: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkBg2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Export Dub & Subtitles",
                    color = DarkFg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = DarkMuted)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Stats badges
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkCard2)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("$segmentCount Subtitles", color = DarkFg, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SkyAccentBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("$translatedCount Translated", color = SkyAccent, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(EmeraldAccentBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("$voicedCount Voiced", color = EmeraldAccent, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Export Actions
            Button(
                onClick = onExportWavDub,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("export_wav_dub_button"),
                colors = ButtonDefaults.buttonColors(containerColor = DarkAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.GraphicEq, contentDescription = "WAV Dub")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Combined AI Dub (.wav)", color = Color.White, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onExportSrt,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("export_srt_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkCard2),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = "SRT", tint = DarkFg)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export .SRT", color = DarkFg, fontSize = 13.sp)
                }

                Button(
                    onClick = onExportVtt,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("export_vtt_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkCard2),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = "VTT", tint = DarkFg)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export .VTT", color = DarkFg, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onExportOriginalSrt,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkCard2),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.Download, contentDescription = "Original", tint = DarkMuted)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Original SRT (Source)", color = DarkMuted, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
