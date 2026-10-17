package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.data.api.GeminiModels
import com.example.media.BlurRegion
import com.example.media.BlurSettings
import com.example.media.BlurStrength
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LanguageOption(val code: String, val label: String)

data class FitMode(val label: String, val rate: Float)

data class AppSettings(
    val apiKey: String = "", // Gemini API key
    val geminiModel: String = GeminiModels.DEFAULT, // see GeminiModels.OPTIONS (3.8 Flash / 3.6 Flash)
    val groqApiKey: String = "",
    val sealionApiKey: String = "",
    val sealionModel: String = "aisingapore/Qwen-SEA-LION-v4.5-27B-IT",
    val sealionUrl: String = "https://api.sea-lion.ai/v1/",
    val transcriptionEngine: String = "gemini", // "gemini" or "groq_whisper"
    val translationEngine: String = "gemini", // "gemini", "sealion", or "custom"
    val customApiName: String = "",
    val customApiUrl: String = "",
    val customApiKey: String = "",
    val customApiModel: String = "",
    val ttsServerUrl: String = "https://takumi0002-tts-kh.hf.space",
    val language: String = "Khmer",
    val femaleVoice: String = "km-KH-SreymomNeural",
    val maleVoice: String = "km-KH-PisethNeural",
    val style: String = "natural, conversational, dramatic movie dubbing",
    val fitIndex: Int = 2,
    val originalVolume: Float = 1.0f,
    val dubVolume: Float = 1.0f,
    val dubEnabled: Boolean = true,
    val showSubtitles: Boolean = true
)

class SettingsRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mst_dubbers_prefs", Context.MODE_PRIVATE)

    companion object {
        /** Placeholder from `.env.example`, compiled in when a build has no `.env` (e.g. CI). Not a real key. */
        private const val PLACEHOLDER_API_KEY = "MY_GEMINI_API_KEY"

        const val ABOUT_TEXT = "នេះជាកម្មវិធី បកប្រែវីដេអូពីភាសាផ្សេងទៅជាភាសាខ្មែរ ឬភាសាផ្សេងទៀត ដោយផ្ទាល់នៅក្នុងកម្មវិធីរបស់យើង អ្នកទាំងអស់គ្នាអាចប្រើប្រាស់ដោយឥតគិតថ្លៃ ដោយត្រឹមតែអ្នកទាំងអស់គ្នាមាន Key API."

        val FIT_MODES = listOf(
            FitMode("Off", 1.0f),
            FitMode("Low", 1.15f),
            FitMode("Medium", 1.35f),
            FitMode("High", 1.6f)
        )

        val LANGUAGES = listOf(
            LanguageOption("Khmer", "ភាសាខ្មែរ (Khmer)"),
            LanguageOption("English", "English"),
            LanguageOption("Thai", "ภาษาไทย (Thai)"),
            LanguageOption("Vietnamese", "Tiếng Việt"),
            LanguageOption("Chinese (Simplified)", "中文 (Simplified)"),
            LanguageOption("Korean", "한국어"),
            LanguageOption("Japanese", "日本語"),
            LanguageOption("Lao", "ພາສາລາວ (Lao)")
        )

        // Edge TTS voices (Khmer + Multilingual)
        val EDGE_FEMALE_VOICES = listOf(
            "km-KH-SreymomNeural",
            "en-US-JennyNeural",
            "th-TH-PremwadeeNeural",
            "vi-VN-HoaiMyNeural",
            "zh-CN-XiaoxiaoNeural"
        )

        val EDGE_MALE_VOICES = listOf(
            "km-KH-PisethNeural",
            "en-US-GuyNeural",
            "th-TH-NiwatNeural",
            "vi-VN-NamMinhNeural",
            "zh-CN-YunxiNeural"
        )

        /**
         * Auto-detect reading speed based on text length:
         * - 50 to 60+ chars: speed increases to 1.4, 1.5 or above
         * - Short text: normal (1.0), 1.1, or 1.2
         * - Also scales with duration if allocated subtitle time is tight
         */
        fun calculateAutoSpeed(text: String, durationSec: Double = 0.0): Float {
            val clean = text.replace(Regex("\\s+"), "")
            val chars = clean.length
            var s = when {
                chars >= 60 -> 1.50f
                chars >= 50 -> 1.40f
                chars >= 35 -> 1.25f
                chars >= 20 -> 1.15f
                chars >= 10 -> 1.10f
                else -> 1.00f
            }
            if (durationSec > 0.0 && chars > 0) {
                val needed = ((chars / durationSec) / 14.0).toFloat()
                if (needed > s) {
                    s = needed
                }
            }
            return (Math.round(s.coerceIn(1.0f, 1.8f) * 10f) / 10f)
        }
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val buildKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Throwable) {
            ""
        }
        val storedKey = prefs.getString("api_key", "") ?: ""
        // Treat the .env.example placeholder as "no key" so the user is asked to enter their own.
        val effectiveKey = (if (storedKey.isNotBlank()) storedKey else buildKey)
            .takeUnless { it.trim() == PLACEHOLDER_API_KEY }
            .orEmpty()

        return AppSettings(
            apiKey = effectiveKey,
            geminiModel = GeminiModels.resolve(prefs.getString("gemini_model", GeminiModels.DEFAULT)),
            groqApiKey = prefs.getString("groq_api_key", "") ?: "",
            sealionApiKey = prefs.getString("sealion_api_key", "") ?: "",
            sealionModel = prefs.getString("sealion_model", "aisingapore/Qwen-SEA-LION-v4.5-27B-IT") ?: "aisingapore/Qwen-SEA-LION-v4.5-27B-IT",
            sealionUrl = prefs.getString("sealion_url", "https://api.sea-lion.ai/v1/") ?: "https://api.sea-lion.ai/v1/",
            transcriptionEngine = prefs.getString("transcription_engine", "gemini") ?: "gemini",
            translationEngine = prefs.getString("translation_engine", "gemini") ?: "gemini",
            customApiName = prefs.getString("custom_api_name", "") ?: "",
            customApiUrl = prefs.getString("custom_api_url", "") ?: "",
            customApiKey = prefs.getString("custom_api_key", "") ?: "",
            customApiModel = prefs.getString("custom_api_model", "") ?: "",
            ttsServerUrl = prefs.getString("tts_server_url", "https://takumi0002-tts-kh.hf.space")
                ?: "https://takumi0002-tts-kh.hf.space",
            language = prefs.getString("language", "Khmer") ?: "Khmer",
            femaleVoice = prefs.getString("female_voice", "km-KH-SreymomNeural") ?: "km-KH-SreymomNeural",
            maleVoice = prefs.getString("male_voice", "km-KH-PisethNeural") ?: "km-KH-PisethNeural",
            style = prefs.getString("style", "natural, conversational, dramatic movie dubbing")
                ?: "natural, conversational, dramatic movie dubbing",
            fitIndex = prefs.getInt("fit_index", 2),
            originalVolume = prefs.getFloat("original_volume", 1.0f),
            dubVolume = prefs.getFloat("dub_volume", 1.0f),
            dubEnabled = prefs.getBoolean("dub_enabled", true),
            showSubtitles = prefs.getBoolean("show_subtitles", true)
        )
    }

    fun saveSettings(updated: AppSettings) {
        prefs.edit()
            .putString("api_key", updated.apiKey.trim())
            .putString("gemini_model", updated.geminiModel)
            .putString("groq_api_key", updated.groqApiKey.trim())
            .putString("sealion_api_key", updated.sealionApiKey.trim())
            .putString("sealion_model", updated.sealionModel.trim())
            .putString("sealion_url", updated.sealionUrl.trim())
            .putString("transcription_engine", updated.transcriptionEngine)
            .putString("translation_engine", updated.translationEngine)
            .putString("custom_api_name", updated.customApiName.trim())
            .putString("custom_api_url", updated.customApiUrl.trim())
            .putString("custom_api_key", updated.customApiKey.trim())
            .putString("custom_api_model", updated.customApiModel.trim())
            .putString("tts_server_url", updated.ttsServerUrl.trim())
            .putString("language", updated.language)
            .putString("female_voice", updated.femaleVoice)
            .putString("male_voice", updated.maleVoice)
            .putString("style", updated.style)
            .apply()

        _settings.value = updated
    }

    fun updateSettings(
        apiKey: String,
        // Optional values default to the current setting so callers that omit them
        // (e.g. the voice picker) don't reset them.
        geminiModel: String = _settings.value.geminiModel,
        groqApiKey: String,
        sealionApiKey: String,
        sealionModel: String = _settings.value.sealionModel,
        sealionUrl: String = _settings.value.sealionUrl,
        transcriptionEngine: String,
        translationEngine: String,
        customApiName: String,
        customApiUrl: String,
        customApiKey: String,
        customApiModel: String,
        ttsServerUrl: String,
        language: String,
        femaleVoice: String,
        maleVoice: String,
        style: String
    ) {
        prefs.edit()
            .putString("api_key", apiKey.trim())
            .putString("gemini_model", geminiModel)
            .putString("groq_api_key", groqApiKey.trim())
            .putString("sealion_api_key", sealionApiKey.trim())
            .putString("sealion_model", sealionModel.trim())
            .putString("sealion_url", sealionUrl.trim())
            .putString("transcription_engine", transcriptionEngine)
            .putString("translation_engine", translationEngine)
            .putString("custom_api_name", customApiName.trim())
            .putString("custom_api_url", customApiUrl.trim())
            .putString("custom_api_key", customApiKey.trim())
            .putString("custom_api_model", customApiModel.trim())
            .putString("tts_server_url", ttsServerUrl.trim())
            .putString("language", language)
            .putString("female_voice", femaleVoice)
            .putString("male_voice", maleVoice)
            .putString("style", style)
            .apply()

        _settings.value = _settings.value.copy(
            apiKey = apiKey.trim(),
            geminiModel = geminiModel,
            groqApiKey = groqApiKey.trim(),
            sealionApiKey = sealionApiKey.trim(),
            sealionModel = sealionModel.trim(),
            sealionUrl = sealionUrl.trim(),
            transcriptionEngine = transcriptionEngine,
            translationEngine = translationEngine,
            customApiName = customApiName.trim(),
            customApiUrl = customApiUrl.trim(),
            customApiKey = customApiKey.trim(),
            customApiModel = customApiModel.trim(),
            ttsServerUrl = ttsServerUrl.trim(),
            language = language,
            femaleVoice = femaleVoice,
            maleVoice = maleVoice,
            style = style
        )
    }

    fun updateFitIndex(index: Int) {
        val safeIndex = index.coerceIn(0, FIT_MODES.lastIndex)
        prefs.edit().putInt("fit_index", safeIndex).apply()
        _settings.value = _settings.value.copy(fitIndex = safeIndex)
    }

    fun updateMix(originalVolume: Float, dubVolume: Float, dubEnabled: Boolean) {
        prefs.edit()
            .putFloat("original_volume", originalVolume)
            .putFloat("dub_volume", dubVolume)
            .putBoolean("dub_enabled", dubEnabled)
            .apply()
        _settings.value = _settings.value.copy(
            originalVolume = originalVolume,
            dubVolume = dubVolume,
            dubEnabled = dubEnabled
        )
    }

    fun toggleShowSubtitles() {
        val next = !_settings.value.showSubtitles
        prefs.edit().putBoolean("show_subtitles", next).apply()
        _settings.value = _settings.value.copy(showSubtitles = next)
    }

    /** The blur box of a project (off, at the default position and strength, if never set). */
    fun loadBlurSettings(projectId: Long): BlurSettings {
        val region = BlurRegion.decode(prefs.getString(blurRegionKey(projectId), null)) ?: BlurRegion.DEFAULT
        return BlurSettings(
            enabled = prefs.getBoolean(blurEnabledKey(projectId), false),
            region = region,
            strength = BlurStrength.clamp(prefs.getFloat(blurStrengthKey(projectId), BlurStrength.DEFAULT))
        )
    }

    fun saveBlurSettings(projectId: Long, blur: BlurSettings) {
        prefs.edit()
            .putBoolean(blurEnabledKey(projectId), blur.enabled)
            .putString(blurRegionKey(projectId), blur.region.sanitized().encode())
            .putFloat(blurStrengthKey(projectId), BlurStrength.clamp(blur.strength))
            .apply()
    }

    fun clearBlurSettings(projectId: Long) {
        prefs.edit()
            .remove(blurEnabledKey(projectId))
            .remove(blurRegionKey(projectId))
            .remove(blurStrengthKey(projectId))
            .apply()
    }

    private fun blurEnabledKey(projectId: Long) = "blur_enabled_$projectId"
    private fun blurRegionKey(projectId: Long) = "blur_region_$projectId"
    private fun blurStrengthKey(projectId: Long) = "blur_strength_$projectId"

    fun clearCache(): Long {
        var freedBytes = 0L
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                freedBytes += file.length()
                file.deleteRecursively()
            }
            val audioDubsDir = java.io.File(context.filesDir, "audio_dubs")
            if (audioDubsDir.exists()) {
                audioDubsDir.listFiles()?.forEach { file ->
                    freedBytes += file.length()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return freedBytes
    }
}
