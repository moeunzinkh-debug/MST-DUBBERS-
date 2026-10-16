package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.media.ExportKind
import com.example.media.ExportStage
import com.example.media.ExportUiState
import com.example.ui.theme.DarkAccent
import com.example.ui.theme.DarkBg2
import com.example.ui.theme.DarkFg
import com.example.ui.theme.DarkLine
import com.example.ui.theme.DarkMuted
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.SkyAccent

/** Progress / result dialog for video and audio exports. Shows nothing when idle. */
@Composable
fun ExportDialog(
    state: ExportUiState,
    blurEnabled: Boolean,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    when (state) {
        ExportUiState.Idle -> Unit

        is ExportUiState.Running -> AlertDialog(
            onDismissRequest = { /* keep open while exporting */ },
            containerColor = DarkBg2,
            title = {
                Text(
                    text = if (state.kind == ExportKind.VIDEO) "Exporting video..." else "Exporting AI voice...",
                    color = DarkFg,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = when (state.stage) {
                            ExportStage.BUILDING_VOICE_TRACK -> "Building the AI voice track (កំពុងរៀបចំសំឡេង AI)"
                            ExportStage.ENCODING_VIDEO ->
                                if (blurEnabled) "Replacing the audio and applying the blur box (កំពុងបញ្ចូលសំឡេង)"
                                else "Replacing the original audio with the AI voice (កំពុងបញ្ចូលសំឡេង)"
                            ExportStage.SAVING -> "Saving to the gallery..."
                        },
                        color = DarkMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            color = SkyAccent,
                            trackColor = DarkLine,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("export_progress")
                        )
                        Text(
                            text = "${(progress * 100).toInt().coerceIn(0, 100)}%",
                            color = DarkFg,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.End)
                        )
                    } else {
                        LinearProgressIndicator(
                            color = SkyAccent,
                            trackColor = DarkLine,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Cancel", color = DarkMuted) }
            }
        )

        is ExportUiState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DarkBg2,
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldAccent) },
            title = {
                Text(
                    text = if (state.kind == ExportKind.VIDEO) "Dubbed video saved" else "AI voice track ready",
                    color = DarkFg,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (state.saved.uri != null) "Saved to ${state.saved.location} (also in your Gallery)."
                        else "Saved to ${state.saved.location}.",
                        color = DarkMuted,
                        fontSize = 13.sp
                    )
                    if (state.skippedClips > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${state.skippedClips} voice clip(s) couldn't be read and were left silent. Regenerate them and export again.",
                            color = Color(0xFFF59E0B),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shareExport(context, state) }, modifier = Modifier.testTag("export_share_button")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = DarkAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", color = DarkAccent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Close", color = DarkMuted) }
            }
        )

        is ExportUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DarkBg2,
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444)) },
            title = { Text("Export failed", color = DarkFg, fontWeight = FontWeight.Bold) },
            text = { Text(state.message, color = DarkMuted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK", color = DarkAccent) }
            }
        )
    }
}

/** Opens the share sheet for an exported file. */
private fun shareExport(context: Context, done: ExportUiState.Done) {
    val uri: Uri = done.saved.uri ?: done.saved.file?.let { file ->
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    } ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = done.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val title = if (done.kind == ExportKind.VIDEO) "Share dubbed video" else "Share AI voice"
    context.startActivity(Intent.createChooser(intent, title))
}
