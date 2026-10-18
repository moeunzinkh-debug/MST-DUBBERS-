package com.example.data

import android.content.Context
import android.net.Uri
import com.example.data.api.EdgeTtsClient
import com.example.data.api.GeminiClient
import com.example.data.api.SpeakableText
import com.example.data.api.TranscriptionClient
import com.example.data.api.TranslationClient
import com.example.data.api.TtsException
import com.example.data.db.AppDatabase
import com.example.data.db.ProjectEntity
import com.example.data.db.ProjectWithStats
import com.example.data.db.SegmentEntity
import com.example.util.SubtitleParser
import com.example.util.WavHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DubberRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val geminiClient: GeminiClient,
    private val transcriptionClient: TranscriptionClient,
    private val translationClient: TranslationClient,
    private val edgeTtsClient: EdgeTtsClient
) {
    private val projectDao = database.projectDao()
    private val segmentDao = database.segmentDao()

    val allProjects: Flow<List<ProjectWithStats>> = projectDao.observeAllProjectsWithStats()

    fun observeProject(id: Long): Flow<ProjectEntity?> = projectDao.observeProjectById(id)

    fun observeSegments(projectId: Long): Flow<List<SegmentEntity>> =
        segmentDao.observeSegmentsForProject(projectId)

    suspend fun getProject(id: Long): ProjectEntity? = projectDao.getProjectById(id)

    suspend fun getSegments(projectId: Long): List<SegmentEntity> =
        segmentDao.getSegmentsForProject(projectId)

    suspend fun createProject(
        name: String,
        videoUri: Uri?,
        subtitleContent: String?
    ): Long = withContext(Dispatchers.IO) {
        val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
        val projectIdTimestamp = System.currentTimeMillis()

        var videoPath = ""
        var videoMime = "video/mp4"
        var duration = 0.0

        if (videoUri != null) {
            val extension = getFileExtension(videoUri) ?: "mp4"
            val resolverMime = try {
                context.contentResolver.getType(videoUri)
            } catch (_: Exception) {
                null
            }
            val extensionMime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension.lowercase())
            videoMime = resolverMime?.takeIf { it.startsWith("video/") }
                ?: extensionMime?.takeIf { it.startsWith("video/") }
                ?: "video/mp4"
            val destFile = File(projectsDir, "video_${projectIdTimestamp}.$extension")
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            videoPath = destFile.absolutePath

            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(destFile.absolutePath)
                val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                if (durStr != null) {
                    duration = durStr.toDouble() / 1000.0
                }
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }

        val project = ProjectEntity(
            name = name.ifBlank { "Untitled Project" },
            videoPath = videoPath,
            videoMime = videoMime,
            duration = duration,
            createdAt = projectIdTimestamp
        )

        val insertedId = projectDao.insertProject(project)

        if (!subtitleContent.isNullOrBlank()) {
            val cues = SubtitleParser.parseSubtitles(subtitleContent)
            val segments = cues.mapIndexed { index, cue ->
                SegmentEntity(
                    projectId = insertedId,
                    idx = index + 1,
                    start = cue.start,
                    end = cue.end,
                    text = cue.text,
                    translation = null,
                    gender = "female"
                )
            }
            if (segments.isNotEmpty()) {
                segmentDao.insertSegments(segments)
            }
        }

        insertedId
    }

    suspend fun updateProjectDuration(projectId: Long, duration: Double) = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: return@withContext
        if (project.duration <= 0.0 && duration > 0.0) {
            projectDao.updateProject(project.copy(duration = duration))
        }
    }

    suspend fun deleteProject(projectId: Long) = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId)
        if (project != null) {
            if (project.videoPath.isNotEmpty()) {
                val vFile = File(project.videoPath)
                if (vFile.exists()) vFile.delete()
            }
            val segments = segmentDao.getSegmentsForProject(projectId)
            for (seg in segments) {
                seg.audioPath?.let { path ->
                    val aFile = File(path)
                    if (aFile.exists()) aFile.delete()
                }
            }
            projectDao.deleteProjectById(projectId)
            settingsRepository.clearBlurSettings(projectId)
        }
    }

    suspend fun updateSegment(segment: SegmentEntity) = withContext(Dispatchers.IO) {
        val existing = segmentDao.getSegmentById(segment.id)
        val invalidateAudio = existing != null && (
                existing.translation != segment.translation ||
                        existing.text != segment.text ||
                        existing.gender != segment.gender
                )

        val updatedSegment = if (invalidateAudio && existing?.audioPath != null) {
            val aFile = File(existing.audioPath)
            if (aFile.exists()) aFile.delete()
            segment.copy(audioPath = null, audioDuration = null)
        } else {
            segment
        }
        segmentDao.updateSegment(updatedSegment)
    }

    suspend fun deleteSegment(segmentId: Long) = withContext(Dispatchers.IO) {
        val existing = segmentDao.getSegmentById(segmentId)
        if (existing?.audioPath != null) {
            val aFile = File(existing.audioPath)
            if (aFile.exists()) aFile.delete()
        }
        segmentDao.deleteSegmentById(segmentId)
    }

    suspend fun updateSegmentSpeed(segmentId: Long, speed: Float?) = withContext(Dispatchers.IO) {
        val seg = segmentDao.getSegmentById(segmentId) ?: return@withContext
        segmentDao.updateSegment(seg.copy(customSpeed = speed))
    }

    suspend fun replaceSubtitles(projectId: Long, content: String): Int = withContext(Dispatchers.IO) {
        val cues = SubtitleParser.parseSubtitles(content)
        if (cues.isEmpty()) return@withContext 0

        val oldSegments = segmentDao.getSegmentsForProject(projectId)
        for (s in oldSegments) {
            s.audioPath?.let { File(it).delete() }
        }
        segmentDao.deleteSegmentsByProjectId(projectId)

        val newSegments = cues.mapIndexed { index, cue ->
            SegmentEntity(
                projectId = projectId,
                idx = index + 1,
                start = cue.start,
                end = cue.end,
                text = cue.text,
                gender = "female"
            )
        }
        segmentDao.insertSegments(newSegments)
        newSegments.size
    }

    suspend fun transcribeVideoWithAI(projectId: Long): Int = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId)
            ?: throw IllegalStateException("Project not found")

        if (project.videoPath.isBlank()) {
            throw IllegalStateException("No video file associated with this project")
        }

        val videoFile = File(project.videoPath)
        if (!videoFile.exists()) {
            throw IllegalStateException("Video file does not exist on disk")
        }

        val engine = settingsRepository.settings.value.transcriptionEngine
        val items = transcriptionClient.transcribe(videoFile, project.videoMime, engine)
        if (items.isEmpty()) {
            throw IllegalStateException("No speech dialogue could be detected in video")
        }

        val oldSegments = segmentDao.getSegmentsForProject(projectId)
        for (s in oldSegments) {
            s.audioPath?.let { File(it).delete() }
        }
        segmentDao.deleteSegmentsByProjectId(projectId)

        val newSegments = items.mapIndexed { index, item ->
            SegmentEntity(
                projectId = projectId,
                idx = index + 1,
                start = item.start,
                end = item.end,
                text = item.text,
                gender = item.gender
            )
        }
        segmentDao.insertSegments(newSegments)
        newSegments.size
    }

    suspend fun translateBatch(
        projectId: Long,
        segmentIds: List<Long>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.value
        val allProjectSegments = segmentDao.getSegmentsForProject(projectId)
        val targets = if (segmentIds.isNotEmpty()) {
            allProjectSegments.filter { it.id in segmentIds }
        } else {
            allProjectSegments.filter { it.translation.isNullOrBlank() }
        }

        if (targets.isEmpty()) return@withContext 0

        val total = targets.size
        var updatedCount = 0
        val batchSize = 20

        for (i in targets.indices step batchSize) {
            val chunk = targets.subList(i, minOf(i + batchSize, targets.size))
            val itemsToTranslate = chunk.map {
                Triple(it.id, (it.end - it.start).coerceAtLeast(0.5), it.text)
            }

            val results = translationClient.translate(
                segments = itemsToTranslate,
                targetLanguage = settings.language,
                style = settings.style,
                engine = settings.translationEngine
            )

            for (res in results) {
                val seg = chunk.find { it.id == res.id }
                if (seg != null && res.translation.isNotBlank()) {
                    segmentDao.updateSegment(seg.copy(translation = res.translation))
                    updatedCount++
                }
            }

            onProgress(minOf(i + chunk.size, total), total)
        }

        updatedCount
    }

    /**
     * Makes the AI voice of one line with the Edge TTS server and saves it on the line.
     *
     * If the server fails and [allowGeminiFallback] is true, Gemini TTS is tried instead (only
     * when a Gemini API key is set). Batch generation turns the fallback on only for the last
     * attempt of a line, so a brief server problem doesn't use up the small Gemini TTS quota.
     *
     * @throws TtsException telling why the line couldn't be voiced and whether retrying may help.
     */
    suspend fun generateVoiceForSegment(
        segmentId: Long,
        allowGeminiFallback: Boolean = true
    ): SegmentEntity = withContext(Dispatchers.IO) {
        val seg = segmentDao.getSegmentById(segmentId)
            ?: throw TtsException("This line no longer exists", TtsException.Kind.PERMANENT)

        val settings = settingsRepository.settings.value
        val voiceName = if (seg.gender.equals("male", ignoreCase = true)) {
            settings.maleVoice
        } else {
            settings.femaleVoice
        }

        val textToSpeak = (seg.translation?.takeIf { it.isNotBlank() } ?: seg.text).trim()
        if (!SpeakableText.hasWords(textToSpeak)) {
            throw TtsException(EdgeTtsClient.NO_WORDS_MESSAGE, TtsException.Kind.NOTHING_TO_SPEAK)
        }

        val audioDir = File(context.filesDir, "audio_dubs").apply { mkdirs() }
        val audioFile = File(audioDir, "a_${seg.id}_${System.currentTimeMillis()}.mp3")

        // Auto-detect reading speed:
        // - Text between 50 to 60+ chars: speed increases to 1.4, 1.5, or above
        // - Short text: 1.0, 1.1, or 1.2
        val autoSpeed = SettingsRepository.calculateAutoSpeed(textToSpeak, seg.end - seg.start)
        val effectiveSpeed = seg.customSpeed ?: autoSpeed

        var durationSec: Double
        try {
            // Direct to user Edge TTS server: https://takumi0002-tts-kh.hf.space
            durationSec = edgeTtsClient.generateSpeech(
                text = textToSpeak,
                voice = voiceName,
                speed = effectiveSpeed,
                destFile = audioFile
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            audioFile.delete()
            val edgeError = TtsException.from(e)
            if (!allowGeminiFallback || settings.apiKey.isBlank() ||
                edgeError.kind == TtsException.Kind.NOTHING_TO_SPEAK
            ) {
                throw edgeError
            }
            // Fallback to Gemini TTS if the Edge server keeps failing
            try {
                val pcmBytes = geminiClient.generateSpeech(
                    text = textToSpeak,
                    voiceName = if (seg.gender.equals("male", ignoreCase = true)) "Charon" else "Kore",
                    stylePrompt = "Say expressively"
                )
                val wavFile = File(audioDir, "a_${seg.id}_${System.currentTimeMillis()}.wav")
                durationSec = WavHelper.saveWavFile(wavFile, pcmBytes)
                wavFile.renameTo(audioFile)
            } catch (geminiError: Exception) {
                if (geminiError is kotlinx.coroutines.CancellationException) throw geminiError
                throw TtsException(
                    "${edgeError.message}. Gemini TTS backup also failed: ${geminiError.message}",
                    edgeError.kind,
                    edgeError.retryAfterSec,
                    geminiError
                )
            }
        }

        seg.audioPath?.let { oldPath ->
            val oldFile = File(oldPath)
            if (oldFile.exists()) oldFile.delete()
        }

        // Audio VAD Snap: detect exact moment dialogue is spoken in the original video audio track.
        val project = projectDao.getProjectById(seg.projectId)
        val videoFile = project?.videoPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        val vadOnset = if (videoFile != null && videoFile.exists()) {
            try {
                com.example.media.AudioVadDetector.detectSpeechOnset(
                    mediaFile = videoFile,
                    startSec = seg.start,
                    endSec = seg.end,
                    ttsDurationSec = durationSec
                )
            } catch (e: Exception) {
                null
            }
        } else null

        // Auto-Snap: use detected VAD speech onset, or fallback to aligning end if shorter than slot.
        val currentSlot = seg.end - seg.start
        val newStart = when {
            vadOnset != null -> vadOnset
            durationSec > 0.0 && durationSec < currentSlot -> {
                val aligned = Math.round((seg.end - durationSec) * 10.0) / 10.0
                aligned.coerceAtLeast(seg.start)
            }
            else -> seg.start
        }

        val updated = seg.copy(
            start = newStart,
            audioPath = audioFile.absolutePath,
            audioDuration = durationSec
        )
        segmentDao.updateSegment(updated)
        updated
    }

    suspend fun shiftAllSegments(projectId: Long, offsetSec: Double) = withContext(Dispatchers.IO) {
        val segs = segmentDao.getSegmentsForProject(projectId)
        if (segs.isEmpty() || offsetSec == 0.0) return@withContext
        val updated = segs.map { seg ->
            val newStart = (seg.start + offsetSec).coerceAtLeast(0.0)
            val newEnd = (seg.end + offsetSec).coerceAtLeast(newStart + 0.2)
            seg.copy(start = newStart, end = newEnd)
        }
        segmentDao.insertSegments(updated)
    }

    suspend fun updateSegmentTimings(
        segmentId: Long,
        newStart: Double,
        newEnd: Double,
        shiftSubsequent: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val seg = segmentDao.getSegmentById(segmentId) ?: return@withContext
        val oldStart = seg.start
        val delta = newStart - oldStart
        val safeStart = newStart.coerceAtLeast(0.0)
        val safeEnd = maxOf(newEnd, safeStart + 0.2)
        if (shiftSubsequent && delta != 0.0) {
            val all = segmentDao.getSegmentsForProject(seg.projectId)
            val updated = all.map { s ->
                when {
                    s.id == segmentId -> s.copy(start = safeStart, end = safeEnd)
                    s.idx > seg.idx -> {
                        val sStart = (s.start + delta).coerceAtLeast(0.0)
                        val sEnd = (s.end + delta).coerceAtLeast(sStart + 0.2)
                        s.copy(start = sStart, end = sEnd)
                    }
                    else -> s
                }
            }
            segmentDao.insertSegments(updated)
        } else {
            segmentDao.updateSegment(seg.copy(start = safeStart, end = safeEnd))
        }
    }

    private fun getFileExtension(uri: Uri): String? {
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        if (!extension.isNullOrBlank()) return extension
        return try {
            val mime = context.contentResolver.getType(uri)
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        } catch (e: Exception) {
            "mp4"
        }
    }
}
