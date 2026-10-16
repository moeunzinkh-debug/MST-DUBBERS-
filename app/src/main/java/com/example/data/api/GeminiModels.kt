package com.example.data.api

/** A Gemini text model that can be picked from the Settings drop-down menu. */
data class GeminiModelOption(
    val id: String,
    val label: String,
    val description: String
)

/**
 * Gemini models used for AI transcription and translation.
 *
 * Model codes follow https://ai.google.dev/gemini-api/docs/models
 */
object GeminiModels {
    const val GEMINI_3_8_FLASH = "gemini-3.8-flash"
    const val GEMINI_3_6_FLASH = "gemini-3.6-flash"

    /** Used when nothing (or a model that is no longer offered) is selected. */
    const val DEFAULT = GEMINI_3_8_FLASH

    val OPTIONS: List<GeminiModelOption> = listOf(
        GeminiModelOption(
            id = GEMINI_3_8_FLASH,
            label = "Gemini 3.8 Flash",
            description = "Newest & most intelligent Flash model (recommended)"
        ),
        GeminiModelOption(
            id = GEMINI_3_6_FLASH,
            label = "Gemini 3.6 Flash",
            description = "Previous-generation Flash model, fast & stable"
        )
    )

    /** Returns [id] when it is one of [OPTIONS], otherwise [DEFAULT]. */
    fun resolve(id: String?): String {
        val clean = id?.trim().orEmpty()
        return OPTIONS.firstOrNull { it.id == clean }?.id ?: DEFAULT
    }

    /** Display name for a model id, e.g. "Gemini 3.8 Flash". */
    fun labelFor(id: String?): String {
        val resolved = resolve(id)
        return OPTIONS.first { it.id == resolved }.label
    }

    /** REST endpoint of `generateContent` for the selected model. */
    fun generateContentUrl(modelId: String?, apiKey: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/${resolve(modelId)}:generateContent?key=$apiKey"
}
