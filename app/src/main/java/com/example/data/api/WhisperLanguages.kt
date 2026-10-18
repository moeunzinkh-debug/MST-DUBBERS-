package com.example.data.api

/**
 * Whisper "Audio Language" options for the Groq transcription engine — the same setting
 * TurboScribe exposes before clicking "Transcribe".
 *
 * Two jobs:
 *  1. It is sent to Groq as the OpenAI-compatible `language` form field, which forces
 *     Whisper to decode that language instead of auto-detecting. Auto-detection regularly
 *     mistakes a Khmer dub for Chinese (Whisper hears the dominant tonal bed and writes the
 *     original script), which is exactly how a "TurboScribe vs Groq" transcript mismatch
 *     happens on the same video.
 *  2. When the video has several audio tracks, the track whose declared language matches is
 *     preferred (see [trackTagsFor]).
 */
object WhisperLanguages {

    const val AUTO = "auto"

    data class Option(val code: String, val label: String)

    val OPTIONS = listOf(
        Option(AUTO, "Auto-detect (ស្វ័យប្រវត្តិ)"),
        Option("km", "ភាសាខ្មែរ (Khmer)"),
        Option("zh", "中文 (Chinese)"),
        Option("en", "English"),
        Option("th", "ภาษาไทย (Thai)"),
        Option("vi", "Tiếng Việt (Vietnamese)"),
        Option("ko", "한국어 (Korean)"),
        Option("ja", "日本語 (Japanese)"),
        Option("lo", "ພາສາລາວ (Lao)"),
        Option("fr", "Français"),
        Option("es", "Español"),
        Option("hi", "हिन्दी (Hindi)")
    )

    /** All selectable ISO-639-1 codes (everything except "auto"). */
    val CODES: Set<String> = OPTIONS.map { it.code }.filter { it != AUTO }.toSet()

    /**
     * Returns a normalized ISO-639-1 code for the Groq `language` field, or null when the
     * setting is "auto"/blank/unknown (the field must then be omitted so Whisper auto-detects).
     */
    fun normalize(value: String?): String? {
        val code = value?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()
        return code.takeIf { it.length == 2 && it in CODES }
    }

    /** Validates a stored setting value, falling back to "auto". */
    fun sanitizeSetting(value: String?): String =
        value?.trim()?.lowercase().takeIf { it == AUTO || normalize(it) != null } ?: AUTO

    fun labelFor(value: String): String =
        OPTIONS.firstOrNull { it.code == sanitizeSetting(value) }?.label ?: OPTIONS.first().label

    /**
     * Language tags a video container may declare for a track that matches [iso], covering
     * both ISO-639-1 ("km") and ISO-639-2/B ("khm") spellings.
     */
    fun trackTagsFor(iso: String?): Set<String> = when (iso) {
        "km" -> setOf("km", "khm")
        "zh" -> setOf("zh", "zho", "chi")
        "en" -> setOf("en", "eng")
        "th" -> setOf("th", "tha")
        "vi" -> setOf("vi", "vie")
        "ko" -> setOf("ko", "kor")
        "ja" -> setOf("ja", "jpn")
        "lo" -> setOf("lo", "lao")
        "fr" -> setOf("fr", "fra", "fre")
        "es" -> setOf("es", "spa")
        "hi" -> setOf("hi", "hin")
        else -> emptySet()
    }

    /**
     * True when [declared] (a track's `KEY_LANGUAGE`, e.g. "km", "khm" or "zh-CN") refers to
     * the same language as ISO-639-1 code [iso].
     */
    fun trackLanguageMatches(declared: String?, iso: String?): Boolean {
        if (iso == null || declared.isNullOrBlank()) return false
        val normalized = declared.trim().lowercase().replace('_', '-').substringBefore('-')
        return normalized in trackTagsFor(iso)
    }
}
