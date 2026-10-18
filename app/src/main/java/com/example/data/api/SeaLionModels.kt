package com.example.data.api

/** A SEA-LION model that can be picked from the Settings drop-down menu. */
data class SeaLionModelOption(
    val id: String,
    val label: String,
    val description: String
)

/**
 * SEA-LION models offered in the Settings drop-down (translation engine "SeaLion").
 *
 * Model codes follow https://api.sea-lion.ai — e.g. `aisingapore/Gemma-SEA-LION-v4-27B-IT`.
 */
object SeaLionModels {
    const val GEMMA_V4_27B = "aisingapore/Gemma-SEA-LION-v4-27B-IT"
    const val QWEN_V4_5_27B = "aisingapore/Qwen-SEA-LION-v4.5-27B-IT"
    const val LLAMA_V3_70B = "aisingapore/Llama-SEA-LION-v3-70B-IT"

    /** Used when nothing is selected yet (fresh installs). */
    const val DEFAULT = GEMMA_V4_27B

    val OPTIONS: List<SeaLionModelOption> = listOf(
        SeaLionModelOption(
            id = GEMMA_V4_27B,
            label = "Gemma SEA-LION v4 27B",
            description = "aisingapore/Gemma-SEA-LION-v4-27B-IT (recommended)"
        ),
        SeaLionModelOption(
            id = QWEN_V4_5_27B,
            label = "Qwen SEA-LION v4.5 27B",
            description = "aisingapore/Qwen-SEA-LION-v4.5-27B-IT"
        ),
        SeaLionModelOption(
            id = LLAMA_V3_70B,
            label = "Llama SEA-LION v3 70B",
            description = "aisingapore/Llama-SEA-LION-v3-70B-IT"
        )
    )

    /**
     * Returns the model id to use: [DEFAULT] when [id] is blank, otherwise the trimmed id.
     * Ids saved by older versions (when this was a free-text field) are kept as-is so an
     * update never silently switches a working setup to another model.
     */
    fun resolve(id: String?): String {
        val clean = id?.trim().orEmpty()
        return clean.ifBlank { DEFAULT }
    }

    /** True when [id] is one of the drop-down [OPTIONS]. */
    fun isOffered(id: String?): Boolean = OPTIONS.any { it.id == id?.trim() }

    /** Display name for a model id, e.g. "Gemma SEA-LION v4 27B" (the raw id if custom). */
    fun labelFor(id: String?): String {
        val resolved = resolve(id)
        return OPTIONS.firstOrNull { it.id == resolved }?.label ?: resolved
    }
}
