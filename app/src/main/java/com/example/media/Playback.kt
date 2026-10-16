package com.example.media

/**
 * Asks the video player to jump to [positionSec]. [token] increases with every request, so
 * tapping the same line twice still seeks twice.
 */
data class SeekRequest(val positionSec: Double, val token: Long)

/** What is being exported. */
enum class ExportKind { VIDEO, AUDIO }

/** Steps of an export, shown in the progress dialog. */
enum class ExportStage { BUILDING_VOICE_TRACK, ENCODING_VIDEO, SAVING }

/** State of the export dialog. */
sealed interface ExportUiState {
    data object Idle : ExportUiState

    /** @param progress 0..1, or null when unknown. */
    data class Running(val kind: ExportKind, val stage: ExportStage, val progress: Float?) : ExportUiState

    /** @param skippedClips voice clips that couldn't be read and were left silent. */
    data class Done(
        val kind: ExportKind,
        val saved: SavedMedia,
        val mimeType: String,
        val skippedClips: Int
    ) : ExportUiState

    data class Failed(val kind: ExportKind, val message: String) : ExportUiState
}
