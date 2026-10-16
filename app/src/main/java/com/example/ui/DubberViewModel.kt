package com.example.ui

import android.app.Application
import android.media.AudioAttributes
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
import com.example.util.Cue
import com.example.util.SubtitleParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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
        viewModelScope.launch {
            repository.observeProject(projectId).collect { proj ->
                _currentProject.value = proj
                proj?.let {
                    if (it.duration > 0.0) _duration.value = it.duration
                }
            }
        }
        viewModelScope.launch {
            repository.observeSegments(projectId).collect { segs ->
                _currentSegments.value = segs
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
        if (!playing) {
            stopDubAudio()
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

    fun seekTo(time: Double) {
        stopDubAudio()
        _currentTime.value = time
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

    private fun checkAndTriggerDubAudio(time: Double) {
        val currentSettings = settings.value
        if (!currentSettings.dubEnabled || !_isPlaying.value) {
            if (_isDubPlaying.value) stopDubAudio()
            return
        }

        val seg = _currentSegments.value.find { time >= it.start && time < it.end }
        if (seg != null && !seg.audioPath.isNullOrBlank()) {
            if (activeDubSegmentId != seg.id) {
                activeDubSegmentId = seg.id
                val offsetSec = (time - seg.start).coerceAtLeast(0.0)
                playDubAudioForSegment(seg, offsetSec)
            }
        } else {
            if (activeDubSegmentId != null) {
                stopDubAudio()
            }
        }
    }

    private fun playDubAudioForSegment(seg: SegmentEntity, offsetSec: Double = 0.0) {
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
                val fitMode = SettingsRepository.FIT_MODES.getOrNull(settings.value.fitIndex)
                val maxRate = fitMode?.rate ?: 1.35f
                val slot = (seg.end - seg.start).coerceAtLeast(0.3)
                val dur = seg.audioDuration ?: slot
                val autoSpeed = (dur / slot).toFloat().coerceIn(1.0f, maxRate)
                val targetSpeed = seg.customSpeed ?: autoSpeed

                setOnPreparedListener { mp ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val params = mp.playbackParams
                            params.speed = targetSpeed
                            mp.playbackParams = params
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    val vol = settings.value.dubVolume.coerceIn(0f, 1f)
                    mp.setVolume(vol, vol)

                    val offsetMs = (offsetSec * 1000).toInt()
                    if (offsetMs > 80 && offsetMs < mp.duration) {
                        mp.seekTo(offsetMs)
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

    fun exportCombinedWavDub(): File? {
        val segs = _currentSegments.value.filter { !it.audioPath.isNullOrBlank() }
        if (segs.isEmpty()) return null

        val appDir = getApplication<Application>().cacheDir
        val exportFile = File(appDir, "${_currentProject.value?.name ?: "dub"}_ai_dub.wav")

        val firstAudio = File(segs.first().audioPath!!)
        if (firstAudio.exists()) {
            firstAudio.copyTo(exportFile, overwrite = true)
            return exportFile
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        stopDubAudio()
    }
}
