package com.example.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppSettings
import com.example.data.DubberRepository
import com.example.data.SettingsRepository
import com.example.data.api.EdgeTtsClient
import com.example.data.api.GeminiClient
import com.example.data.api.TranscriptionClient
import com.example.data.api.TranslationClient
import com.example.data.db.AppDatabase
import com.example.data.db.ProjectEntity
import com.example.data.db.ProjectWithStats
import com.example.data.db.SegmentEntity
import com.example.media.BlurRegion
import com.example.media.BlurSettings
import com.example.media.DubTimeline
import com.example.media.DubTrackRenderer
import com.example.media.ExportKind
import com.example.media.ExportStage
import com.example.media.ExportUiState
import com.example.media.MediaSaver
import com.example.media.SavedMedia
import com.example.media.SeekRequest
import com.example.media.VideoExporter
import com.example.util.Cue
import com.example.util.SubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProgressState(val done: Int, val total: Int)

class DubberViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    val settingsRepository = SettingsRepository(application)

    private val geminiClient = GeminiClient(
        apiKeyProvider = { settingsRepository.settings.value.apiKey },
        modelProvider = { settingsRepository.settings.value.geminiModel }
    )

    private val transcriptionClient = TranscriptionClient(
        geminiKeyProvider = { settingsRepository.settings.value.apiKey },
        groqKeyProvider = { settingsRepository.settings.value.groqApiKey },
        geminiModelProvider = { settingsRepository.settings.value.geminiModel }
    )

    private val translationClient = TranslationClient(
        geminiKeyProvider = { settingsRepository.settings.value.apiKey },
        sealionKeyProvider = { settingsRepository.settings.value.sealionApiKey },
        sealionModelProvider = { settingsRepository.settings.value.sealionModel },
        sealionUrlProvider = { settingsRepository.settings.value.sealionUrl },
        customConfigProvider = {
            val s = settingsRepository.settings.value
            TranslationClient.Quadruple(
                s.customApiName,
                s.customApiUrl,
                s.customApiKey,
                s.customApiModel
            )
        },
        geminiModelProvider = { settingsRepository.settings.value.geminiModel }
    )

    private val edgeTtsClient = EdgeTtsClient {
        settingsRepository.settings.value.ttsServerUrl
    }

    val repository = DubberRepository(
        context = application,
        database = database,
        settingsRepository = settingsRepository,
        geminiClient = geminiClient,
        transcriptionClient = transcriptionClient,
        translationClient = translationClient,
        edgeTtsClient = edgeTtsClient
    )

    val allProjects: StateFlow<List<ProjectWithStats>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    // Current Project Editor State
    private val _currentProject = MutableStateFlow<ProjectEntity?>(null)
    val currentProject: StateFlow<ProjectEntity?> = _currentProject.asStateFlow()

    private val _currentSegments = MutableStateFlow<List<SegmentEntity>>(emptyList())
    val currentSegments: StateFlow<List<SegmentEntity>> = _currentSegments.asStateFlow()

    private val _selectedSegmentIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSegmentIds: StateFlow<Set<Long>> = _selectedSegmentIds.asStateFlow()

    // Playback state
    private val _currentTime = MutableStateFlow(0.0)
    val currentTime: StateFlow<Double> = _currentTime.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // Action progress states
    private val _translatingProgress = MutableStateFlow<ProgressState?>(null)
    val translatingProgress: StateFlow<ProgressState?> = _translatingProgress.asStateFlow()

    private val _voicingProgress = MutableStateFlow<ProgressState?>(null)
    val voicingProgress: StateFlow<ProgressState?> = _voicingProgress.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _busySegmentIds = MutableStateFlow<Set<Long>>(emptySet())
    val busySegmentIds: StateFlow<Set<Long>> = _busySegmentIds.asStateFlow()

    private val _toastMessage = MutableStateFlow<Pair<String, Boolean>?>(null)
    val toastMessage: StateFlow<Pair<String, Boolean>?> = _toastMessage.asStateFlow()

    private val _isDubPlaying = MutableStateFlow(false)
    val isDubPlaying: StateFlow<Boolean> = _isDubPlaying.asStateFlow()

    private val _currentlyPlayingSegmentId = MutableStateFlow<Long?>(null)
    val currentlyPlayingSegmentId: StateFlow<Long?> = _currentlyPlayingSegmentId.asStateFlow()

    // Active dub audio player
    private var dubMediaPlayer: MediaPlayer? = null
    private var activeDubSegmentId: Long? = null

    /** Line whose voice was last started by the timeline; each line is voiced once per pass. */
    private var lastDubTriggerSegmentId: Long? = null

    // Seeking: the player observes these requests (tapping a line, the seek bar, ...)
    private val _seekRequest = MutableStateFlow<SeekRequest?>(null)
    val seekRequest: StateFlow<SeekRequest?> = _seekRequest.asStateFlow()
    private var seekToken = 0L

    // Blur box of the current project
    private val _blurSettings = MutableStateFlow(BlurSettings())
    val blurSettings: StateFlow<BlurSettings> = _blurSettings.asStateFlow()

    // Video / audio export
    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()
    private var exportJob: Job? = null

    private var loadedProjectId: Long? = null
    private var projectJobs: List<Job> = emptyList()

    fun showToast(msg: String, isError: Boolean = false) {
        _toastMessage.value = Pair(msg, isError)
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun clearCache() {
        viewModelScope.launch {
            val bytes = settingsRepository.clearCache()
            val mb = bytes / (1024.0 * 1024.0)
            showToast("Cleared cache: %.1f MB freed".format(mb))
        }
    }

    fun loadProject(projectId: Long) {
        // Stop observing the previously opened project, otherwise its updates would leak in.
        projectJobs.forEach { it.cancel() }
        if (loadedProjectId != projectId) {
            loadedProjectId = projectId
            stopDubAudio()
            lastDubTriggerSegmentId = null
            _isPlaying.value = false
            _currentTime.value = 0.0
            _duration.value = 0.0
            _currentProject.value = null
            _currentSegments.value = emptyList()
            _selectedSegmentIds.value = emptySet()
            _seekRequest.value = SeekRequest(0.0, ++seekToken)
            _blurSettings.value = settingsRepository.loadBlurSettings(projectId)
        }
        projectJobs = listOf(
            viewModelScope.launch {
                repository.observeProject(projectId).collect { proj ->
                    _currentProject.value = proj
                    proj?.let {
                        if (it.duration > 0.0) _duration.value = it.duration
                    }
                }
            },
            viewModelScope.launch {
                repository.observeSegments(projectId).collect { segs ->
                    _currentSegments.value = segs
                }
            }
        )
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
        if (!playing) {
            stopDubAudio()
            lastDubTriggerSegmentId = null
        }
    }

    fun setTime(time: Double) {
        _currentTime.value = time
        checkAndTriggerDubAudio(time)
    }

    fun setDuration(dur: Double) {
        if (dur > 0.0) {
            _duration.value = dur
            _currentProject.value?.let { proj ->
                if (proj.duration <= 0.0) {
                    viewModelScope.launch {
                        repository.updateProjectDuration(proj.id, dur)
                    }
                }
            }
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    /** Moves the video to [time] (seconds), whether it is playing or paused. */
    fun seekTo(time: Double) {
        stopDubAudio()
        lastDubTriggerSegmentId = null
        val duration = _duration.value
        val target = if (duration > 0.0) time.coerceIn(0.0, duration) else time.coerceAtLeast(0.0)
        _currentTime.value = target
        _seekRequest.value = SeekRequest(target, ++seekToken)
    }

    /** Jumps to the start of a subtitle line (tapping a line in the list). */
    fun seekToSegment(segment: SegmentEntity) {
        // A hair after the start so this line, not the previous one, is the active one.
        seekTo(segment.start + 0.05)
    }

    fun toggleSegmentSelection(id: Long) {
        val current = _selectedSegmentIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedSegmentIds.value = current
    }

    fun toggleSelectAll() {
        val allIds = _currentSegments.value.map { it.id }.toSet()
        if (_selectedSegmentIds.value.size == allIds.size) {
            _selectedSegmentIds.value = emptySet()
        } else {
            _selectedSegmentIds.value = allIds
        }
    }

    fun updateSegment(segment: SegmentEntity) {
        viewModelScope.launch {
            try {
                repository.updateSegment(segment)
            } catch (e: Exception) {
                showToast("Failed to update segment: ${e.message}", true)
            }
        }
    }

    fun deleteSegment(segmentId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteSegment(segmentId)
            } catch (e: Exception) {
                showToast("Failed to delete segment: ${e.message}", true)
            }
        }
    }

    fun deleteCurrentProject(onComplete: () -> Unit) {
        val projId = _currentProject.value?.id ?: return
        viewModelScope.launch {
            try {
                repository.deleteProject(projId)
                onComplete()
            } catch (e: Exception) {
                showToast("Failed to delete project: ${e.message}", true)
            }
        }
    }

    fun deleteProjectById(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteProject(id)
            } catch (e: Exception) {
                showToast("Failed to delete project: ${e.message}", true)
            }
        }
    }

    fun startTranscribeAI() {
        val proj = _currentProject.value ?: return
        _isTranscribing.value = true
        viewModelScope.launch {
            try {
                val count = repository.transcribeVideoWithAI(proj.id)
                showToast("Transcribed $count subtitle segments successfully!")
            } catch (e: Exception) {
                showToast("Transcription failed: ${e.message}", true)
            } finally {
                _isTranscribing.value = false
            }
        }
    }

    fun importSubtitles(content: String) {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            try {
                val count = repository.replaceSubtitles(proj.id, content)
                showToast("Imported $count subtitles successfully!")
            } catch (e: Exception) {
                showToast("Failed to import subtitles: ${e.message}", true)
            }
        }
    }

    fun startBatchTranslate() {
        val proj = _currentProject.value ?: return
        val targetIds = _selectedSegmentIds.value.toList()
        _translatingProgress.value = ProgressState(0, if (targetIds.isNotEmpty()) targetIds.size else _currentSegments.value.size)

        viewModelScope.launch {
            try {
                val count = repository.translateBatch(
                    projectId = proj.id,
                    segmentIds = targetIds
                ) { done, total ->
                    _translatingProgress.value = ProgressState(done, total)
                }
                showToast("Translated $count segments successfully!")
            } catch (e: Exception) {
                showToast("Translation error: ${e.message}", true)
            } finally {
                _translatingProgress.value = null
            }
        }
    }

    fun startBatchVoice() {
        val proj = _currentProject.value ?: return
        val allSegs = _currentSegments.value
        val targets = if (_selectedSegmentIds.value.isNotEmpty()) {
            allSegs.filter { it.id in _selectedSegmentIds.value }
        } else {
            allSegs.filter { it.audioPath.isNullOrBlank() }
        }

        if (targets.isEmpty()) {
            showToast("All targeted segments already voiced!")
            return
        }

        _voicingProgress.value = ProgressState(0, targets.size)

        viewModelScope.launch {
            var doneCount = 0
            var hasError: String? = null
            for (seg in targets) {
                try {
                    repository.generateVoiceForSegment(seg.id)
                    doneCount++
                    _voicingProgress.value = ProgressState(doneCount, targets.size)
                } catch (e: Exception) {
                    hasError = e.message
                    break
                }
            }
            _voicingProgress.value = null
            if (hasError != null) {
                showToast("Voice generation halted: $hasError", true)
            } else {
                showToast("Successfully generated Edge TTS voice for $doneCount segments!")
            }
        }
    }

    fun updateSegmentSpeed(segmentId: Long, speed: Float?, regenerateNow: Boolean = false) {
        viewModelScope.launch {
            try {
                repository.updateSegmentSpeed(segmentId, speed)
                if (regenerateNow) {
                    generateVoiceForSingleSegment(segmentId, autoPlay = false)
                } else {
                    showToast(if (speed != null) "Speed set to %.2fx".format(speed) else "Speed reset to Auto")
                }
            } catch (e: Exception) {
                showToast("Failed to update speed: ${e.message}", true)
            }
        }
    }

    fun generateVoiceForSingleSegment(segmentId: Long, autoPlay: Boolean = true) {
        val busy = _busySegmentIds.value.toMutableSet()
        busy.add(segmentId)
        _busySegmentIds.value = busy

        viewModelScope.launch {
            try {
                val updated = repository.generateVoiceForSegment(segmentId)
                if (autoPlay) {
                    playAudioFile(updated.audioPath, updated.id, updated.customSpeed)
                }
            } catch (e: Exception) {
                showToast("TTS Error: ${e.message}", true)
            } finally {
                val nextBusy = _busySegmentIds.value.toMutableSet()
                nextBusy.remove(segmentId)
                _busySegmentIds.value = nextBusy
            }
        }
    }

    fun previewOrGenerateAudio(segment: SegmentEntity) {
        if (_currentlyPlayingSegmentId.value == segment.id) {
            stopDubAudio()
            return
        }
        if (!segment.audioPath.isNullOrBlank()) {
            playAudioFile(segment.audioPath, segment.id, segment.customSpeed)
        } else {
            generateVoiceForSingleSegment(segment.id, autoPlay = true)
        }
    }

    private fun fitMaxRate(): Float =
        SettingsRepository.FIT_MODES.getOrNull(settings.value.fitIndex)?.rate ?: DubTimeline.DEFAULT_MAX_RATE

    private fun checkAndTriggerDubAudio(time: Double) {
        if (!settings.value.dubEnabled || !_isPlaying.value) {
            if (activeDubSegmentId != null) stopDubAudio()
            return
        }
        // Between two lines: let the current voice finish (the export does the same).
        val seg = _currentSegments.value.find { time >= it.start && time < it.end } ?: return
        if (seg.id == lastDubTriggerSegmentId) return
        lastDubTriggerSegmentId = seg.id

        if (!seg.audioPath.isNullOrBlank()) {
            val rate = DubTimeline.playbackRate(seg, fitMaxRate())
            // Starting mid-line (after a seek): skip the part of the voice that is already past.
            val mediaOffsetSec = (time - seg.start).coerceAtLeast(0.0) * rate
            playDubAudioForSegment(seg, rate, mediaOffsetSec)
        } else if (activeDubSegmentId != null) {
            // The next line has no voice: cut the previous one, as the export does.
            stopDubAudio()
        }
    }

    private fun playDubAudioForSegment(seg: SegmentEntity, rate: Float, mediaOffsetSec: Double) {
        val path = seg.audioPath ?: return
        val file = File(path)
        if (!file.exists()) return

        try {
            stopDubAudio()
            activeDubSegmentId = seg.id
            _currentlyPlayingSegmentId.value = seg.id

            dubMediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                setOnPreparedListener { mp ->
                    if (dubMediaPlayer != mp) {
                        mp.release()
                        return@setOnPreparedListener
                    }
                    val offsetMs = (mediaOffsetSec * 1000).toInt()
                    if (offsetMs >= mp.duration - 50) {
                        // This line's voice would already be over at this position.
                        mp.release()
                        dubMediaPlayer = null
                        activeDubSegmentId = null
                        _currentlyPlayingSegmentId.value = null
                        _isDubPlaying.value = false
                        return@setOnPreparedListener
                    }
                    val vol = settings.value.dubVolume.coerceIn(0f, 1f)
                    mp.setVolume(vol, vol)
                    if (offsetMs > 80) mp.seekTo(offsetMs)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            mp.playbackParams = mp.playbackParams.setSpeed(rate)
                        } catch (e: Exception) {
                            // Ignore: play at normal speed
                        }
                    }
                    mp.start()
                    _isDubPlaying.value = true
                }
                setOnCompletionListener {
                    it.release()
                    if (dubMediaPlayer == it) {
                        dubMediaPlayer = null
                        activeDubSegmentId = null
                        _currentlyPlayingSegmentId.value = null
                        _isDubPlaying.value = false
                    }
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (dubMediaPlayer == mp) {
                        dubMediaPlayer = null
                        activeDubSegmentId = null
                        _currentlyPlayingSegmentId.value = null
                        _isDubPlaying.value = false
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            dubMediaPlayer = null
            activeDubSegmentId = null
            _currentlyPlayingSegmentId.value = null
            _isDubPlaying.value = false
        }
    }

    private fun playAudioFile(path: String?, segmentId: Long? = null, customSpeed: Float? = null) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (!file.exists()) return

        try {
            stopDubAudio()
            _currentlyPlayingSegmentId.value = segmentId
            dubMediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    if (customSpeed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val params = mp.playbackParams
                            params.speed = customSpeed
                            mp.playbackParams = params
                        } catch (e: Exception) {}
                    }
                    val vol = settings.value.dubVolume.coerceIn(0f, 1f)
                    mp.setVolume(vol, vol)
                    mp.start()
                    _isDubPlaying.value = true
                }
                setOnCompletionListener {
                    it.release()
                    if (dubMediaPlayer == it) {
                        dubMediaPlayer = null
                        _currentlyPlayingSegmentId.value = null
                        _isDubPlaying.value = false
                    }
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (dubMediaPlayer == mp) {
                        dubMediaPlayer = null
                        _currentlyPlayingSegmentId.value = null
                        _isDubPlaying.value = false
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            showToast("Cannot play audio: ${e.message}", true)
            _currentlyPlayingSegmentId.value = null
            _isDubPlaying.value = false
        }
    }

    fun stopDubAudio() {
        try {
            dubMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            dubMediaPlayer = null
            activeDubSegmentId = null
            _currentlyPlayingSegmentId.value = null
            _isDubPlaying.value = false
        }
    }

    fun exportSrt(isOriginal: Boolean): String {
        val segs = _currentSegments.value
        val cues = segs.map {
            Cue(
                start = it.start,
                end = it.end,
                text = if (isOriginal) it.text else (it.translation ?: it.text)
            )
        }
        return SubtitleParser.toSrt(cues)
    }

    fun exportVtt(): String {
        val segs = _currentSegments.value
        val cues = segs.map {
            Cue(
                start = it.start,
                end = it.end,
                text = it.translation ?: it.text
            )
        }
        return SubtitleParser.toVtt(cues)
    }

    // ---------------------------------------------------------------- Blur box

    fun toggleBlur() = setBlurEnabled(!_blurSettings.value.enabled)

    fun setBlurEnabled(enabled: Boolean) {
        val updated = _blurSettings.value.copy(enabled = enabled)
        _blurSettings.value = updated
        persistBlur(updated)
    }

    /** Live update while the box is being dragged; call [commitBlurRegion] when the drag ends. */
    fun updateBlurRegion(region: BlurRegion) {
        _blurSettings.value = _blurSettings.value.copy(region = region.sanitized())
    }

    fun commitBlurRegion() = persistBlur(_blurSettings.value)

    private fun persistBlur(blur: BlurSettings) {
        loadedProjectId?.let { settingsRepository.saveBlurSettings(it, blur) }
    }

    // ---------------------------------------------------------------- Export

    /**
     * Exports the dubbed video (the AI voice replaces the original audio; the blur box, if on, is
     * burned in) or, for [ExportKind.AUDIO], just the full-length AI voice track as WAV.
     */
    fun startExport(kind: ExportKind) {
        if (exportJob?.isActive == true) return
        val project = _currentProject.value ?: return
        val videoFile = project.videoPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (kind == ExportKind.VIDEO && (videoFile == null || !videoFile.exists())) {
            showToast("This project has no video file to export.", true)
            return
        }
        setPlaying(false)
        val segments = _currentSegments.value
        val blur = _blurSettings.value.takeIf { it.enabled }?.region
        val app = getApplication<Application>()

        exportJob = viewModelScope.launch {
            try {
                _exportState.value = ExportUiState.Running(kind, ExportStage.BUILDING_VOICE_TRACK, 0f)
                val fileDuration = if (videoFile != null && videoFile.exists()) {
                    withContext(Dispatchers.IO) { readDurationSec(videoFile) }
                } else 0.0
                val durationSec = fileDuration.takeIf { it > 0.0 }
                    ?: _duration.value.takeIf { it > 0.0 }
                    ?: (segments.maxOfOrNull { it.end } ?: 0.0)

                val clips = DubTimeline.plan(segments, fitMaxRate(), durationSec)
                if (clips.isEmpty()) {
                    throw IllegalStateException("No AI voice yet. Tap \"Edge TTS Dub\" to generate the voices first.")
                }

                val workDir = File(app.cacheDir, "export").apply { mkdirs() }
                val baseName = MediaSaver.safeFileName("${project.name}_dub_${timestamp()}")
                val wavFile = File(workDir, if (kind == ExportKind.AUDIO) "$baseName.wav" else "dub_track.wav")
                val track = withContext(Dispatchers.IO) {
                    DubTrackRenderer.render(
                        clips = clips,
                        durationSec = durationSec,
                        output = wavFile,
                        onClipDone = { done, total ->
                            _exportState.value = ExportUiState.Running(
                                kind, ExportStage.BUILDING_VOICE_TRACK, done.toFloat() / total.coerceAtLeast(1)
                            )
                        },
                        checkCancelled = { ensureActive() }
                    )
                }
                if (track.clipsWritten == 0) {
                    throw IllegalStateException("Could not read the AI voice files. Regenerate the voices and try again.")
                }

                if (kind == ExportKind.AUDIO) {
                    _exportState.value = ExportUiState.Done(
                        kind, SavedMedia(null, wavFile, "App cache"), "audio/wav", track.clipsFailed
                    )
                    return@launch
                }

                _exportState.value = ExportUiState.Running(kind, ExportStage.ENCODING_VIDEO, 0f)
                val mp4File = File(workDir, "$baseName.mp4")
                VideoExporter(app).export(
                    videoFile = videoFile!!,
                    audioFile = wavFile,
                    blur = blur,
                    output = mp4File
                ) { percent ->
                    _exportState.value = ExportUiState.Running(kind, ExportStage.ENCODING_VIDEO, percent / 100f)
                }
                wavFile.delete()

                _exportState.value = ExportUiState.Running(kind, ExportStage.SAVING, null)
                val saved = withContext(Dispatchers.IO) { MediaSaver.saveVideo(app, mp4File, mp4File.name) }
                _exportState.value = ExportUiState.Done(kind, saved, "video/mp4", track.clipsFailed)
            } catch (e: CancellationException) {
                _exportState.value = ExportUiState.Idle
                throw e
            } catch (e: Exception) {
                _exportState.value = ExportUiState.Failed(kind, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _exportState.value = ExportUiState.Idle
    }

    fun dismissExportResult() {
        if (_exportState.value !is ExportUiState.Running) _exportState.value = ExportUiState.Idle
    }

    private fun readDurationSec(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun onCleared() {
        super.onCleared()
        stopDubAudio()
    }
}
