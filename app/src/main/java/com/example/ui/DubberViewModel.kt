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
import com.example.data.VoiceBatchOutcome
import com.example.data.VoiceBatchProgress
import com.example.data.VoiceBatchRunner
import com.example.data.api.EdgeTtsClient
import com.example.data.api.GeminiClient
import com.example.data.api.TranscriptionClient
import com.example.data.api.TranslationClient
import com.example.data.api.TtsException
import com.example.data.db.AppDatabase
import com.example.data.db.ProjectEntity
import com.example.data.db.ProjectWithStats
import com.example.data.db.SegmentEntity
import com.example.media.AudioClipDecoder
import com.example.media.BlurRegion
import com.example.media.BlurSettings
import com.example.media.BlurStrength
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

/** Why a line has no AI voice after the last generation run. */
data class VoiceIssue(
    val message: String,
    /** True for lines with nothing to read (only symbols); false for real failures. */
    val skipped: Boolean
)

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

    private val _voicingProgress = MutableStateFlow<VoiceBatchProgress?>(null)
    val voicingProgress: StateFlow<VoiceBatchProgress?> = _voicingProgress.asStateFlow()
    private var voiceJob: Job? = null

    /** Lines that couldn't be voiced in the last run, with the reason (shown on the line). */
    private val _voiceIssues = MutableStateFlow<Map<Long, VoiceIssue>>(emptyMap())
    val voiceIssues: StateFlow<Map<Long, VoiceIssue>> = _voiceIssues.asStateFlow()

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
            voiceJob?.cancel()
            voiceJob = null
            _voicingProgress.value = null
            _voiceIssues.value = emptyMap()
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
        } else {
            stopDubAudio()
            lastDubTriggerSegmentId = null
            checkAndTriggerDubAudio(_currentTime.value)
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
        if (_isPlaying.value) {
            checkAndTriggerDubAudio(target)
        }
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
        // The text or voice may change: the old problem no longer applies.
        clearVoiceIssue(segment.id)
        viewModelScope.launch {
            try {
                repository.updateSegment(segment)
            } catch (e: Exception) {
                showToast("Failed to update segment: ${e.message}", true)
            }
        }
    }

    fun updateSegmentWithShift(segment: SegmentEntity, shiftSubsequent: Boolean) {
        clearVoiceIssue(segment.id)
        viewModelScope.launch {
            try {
                if (shiftSubsequent) {
                    repository.updateSegmentTimings(
                        segmentId = segment.id,
                        newStart = segment.start,
                        newEnd = segment.end,
                        shiftSubsequent = true
                    )
                }
                repository.updateSegment(segment)
            } catch (e: Exception) {
                showToast("Failed to update segment: ${e.message}", true)
            }
        }
    }

    fun shiftAllSegments(offsetSec: Double) {
        val proj = _currentProject.value ?: return
        if (offsetSec == 0.0) return
        viewModelScope.launch {
            try {
                repository.shiftAllSegments(proj.id, offsetSec)
                val sign = if (offsetSec > 0) "+%.1fs".format(offsetSec) else "%.1fs".format(offsetSec)
                showToast("Shifted timeline by $sign")
            } catch (e: Exception) {
                showToast("Failed to shift timeline: ${e.message}", true)
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

    /**
     * Generates the AI voice of the selected lines, or of every line without one. One failing
     * line no longer stops the run (see [VoiceBatchRunner]). Calling it again while it runs
     * stops it.
     */
    fun startBatchVoice() {
        if (voiceJob?.isActive == true) {
            voiceJob?.cancel()
            return
        }
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

        _voicingProgress.value = VoiceBatchProgress(0, targets.size)
        voiceJob = viewModelScope.launch {
            try {
                val outcome = VoiceBatchRunner().run(
                    segments = targets,
                    generate = { seg, lastAttempt ->
                        repository.generateVoiceForSegment(seg.id, allowGeminiFallback = lastAttempt)
                    },
                    onLineDone = { seg, error -> recordVoiceResult(seg.id, error) },
                    onProgress = { _voicingProgress.value = it }
                )
                showToast(batchSummary(outcome, targets), isError = outcome.failed.isNotEmpty())
            } catch (e: CancellationException) {
                showToast("Voice generation stopped")
                throw e
            } finally {
                _voicingProgress.value = null
            }
        }
    }

    private fun recordVoiceResult(segmentId: Long, error: TtsException?) {
        val issues = _voiceIssues.value.toMutableMap()
        if (error == null) {
            issues.remove(segmentId)
        } else {
            issues[segmentId] = VoiceIssue(
                message = error.message ?: "Unknown error",
                skipped = error.kind == TtsException.Kind.NOTHING_TO_SPEAK
            )
        }
        _voiceIssues.value = issues
    }

    private fun clearVoiceIssue(segmentId: Long) {
        if (segmentId in _voiceIssues.value) _voiceIssues.value = _voiceIssues.value - segmentId
    }

    private fun batchSummary(outcome: VoiceBatchOutcome, targets: List<SegmentEntity>): String {
        val lineNumbers = targets.associate { it.id to it.idx }
        fun numbers(ids: Collection<Long>): String {
            val list = ids.mapNotNull { lineNumbers[it] }.sorted()
            val shown = list.take(6).joinToString(", ") { "#$it" }
            return if (list.size > 6) "$shown, ..." else shown
        }
        val parts = mutableListOf("Voiced ${outcome.voiced.size} of ${targets.size} lines")
        if (outcome.skipped.isNotEmpty()) {
            parts += "skipped ${outcome.skipped.size} with no words to read (${numbers(outcome.skipped.keys)})"
        }
        if (outcome.failed.isNotEmpty()) {
            parts += "${outcome.failed.size} failed (${numbers(outcome.failed.keys)}): ${sentence(outcome.failed.values.first())}"
        }
        var text = parts.joinToString(". ") + "."
        if (outcome.stoppedEarly) {
            text += " Stopped because several lines in a row failed: check the internet or the TTS server."
        }
        if (outcome.failed.isNotEmpty()) text += " Tap Edge TTS Dub again to retry the missing lines."
        return text
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
        if (segmentId in _busySegmentIds.value) return
        val segment = _currentSegments.value.find { it.id == segmentId } ?: return
        _busySegmentIds.value = _busySegmentIds.value + segmentId

        viewModelScope.launch {
            try {
                var updated: SegmentEntity? = null
                val outcome = VoiceBatchRunner().run(
                    segments = listOf(segment),
                    generate = { seg, lastAttempt ->
                        updated = repository.generateVoiceForSegment(seg.id, allowGeminiFallback = lastAttempt)
                    },
                    onLineDone = { seg, error -> recordVoiceResult(seg.id, error) }
                )
                val result = updated
                when {
                    result != null -> if (autoPlay) {
                        if (_duration.value > 0.0 || !_currentProject.value?.videoPath.isNullOrBlank()) {
                            seekTo(result.start)
                            setPlaying(true)
                        } else {
                            playAudioFile(result.audioPath, result.id, result.customSpeed)
                        }
                    }
                    outcome.skipped.isNotEmpty() -> showToast(
                        "Line ${segment.idx}: ${sentence(outcome.skipped.values.first())}. Edit the text, or leave this line silent.",
                        true
                    )
                    else -> showToast("Line ${segment.idx}: ${sentence(outcome.failed.values.firstOrNull() ?: "voice failed")}.", true)
                }
            } finally {
                _busySegmentIds.value = _busySegmentIds.value - segmentId
            }
        }
    }

    /** Explains why a line has no voice (tapping its warning icon). */
    fun showVoiceIssue(segment: SegmentEntity) {
        val issue = _voiceIssues.value[segment.id] ?: return
        val hint = if (issue.skipped) "Edit the text, or leave this line silent." else "Tap its play button to try again."
        showToast("Line ${segment.idx}: ${sentence(issue.message)}. $hint", isError = !issue.skipped)
    }

    /** [message] without a final period, to append more text after it. */
    private fun sentence(message: String): String = message.trim().removeSuffix(".")

    fun previewOrGenerateAudio(segment: SegmentEntity) {
        if (_currentlyPlayingSegmentId.value == segment.id && _isPlaying.value) {
            setPlaying(false)
            return
        }
        if (!segment.audioPath.isNullOrBlank()) {
            if (_duration.value > 0.0 || !_currentProject.value?.videoPath.isNullOrBlank()) {
                seekTo(segment.start)
                setPlaying(true)
            } else {
                playAudioFile(segment.audioPath, segment.id, segment.customSpeed)
            }
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

    /**
     * Live update while the strength slider moves (turns the blur on, so the change is visible);
     * call [commitBlurSettings] when the slider is released.
     */
    fun updateBlurStrength(strength: Float) {
        _blurSettings.value = _blurSettings.value.copy(strength = BlurStrength.clamp(strength), enabled = true)
    }

    fun commitBlurSettings() = persistBlur(_blurSettings.value)

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
        val blurSigma = BlurStrength.sigmaFraction(_blurSettings.value.strength)
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

                val effectiveBgVolume = if (_isMuted.value) 0f else settings.value.originalVolume
                val dubVol = settings.value.dubVolume
                val bgAudio = if (kind == ExportKind.VIDEO && effectiveBgVolume > 0f && videoFile != null && videoFile.exists()) {
                    withContext(Dispatchers.IO) {
                        try {
                            AudioClipDecoder.decodeToMono(videoFile)
                        } catch (t: Throwable) {
                            null
                        }
                    }
                } else null

                val track = withContext(Dispatchers.IO) {
                    DubTrackRenderer.render(
                        clips = clips,
                        durationSec = durationSec,
                        output = wavFile,
                        backgroundAudio = bgAudio,
                        backgroundVolume = effectiveBgVolume,
                        duckingFactor = 0.10f,
                        dubVolume = dubVol,
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
                    output = mp4File,
                    blurSigmaFraction = blurSigma
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
