package com.example.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranslationClient(
    private val geminiKeyProvider: () -> String,
    private val sealionKeyProvider: () -> String,
    private val sealionModelProvider: () -> String = { SeaLionModels.DEFAULT },
    private val sealionUrlProvider: () -> String = { "https://api.sea-lion.ai/v1/" },
    private val customConfigProvider: () -> Quadruple<String, String, String, String>, // name, url, key, model
    private val geminiModelProvider: () -> String = { GeminiModels.DEFAULT }
) {
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun translate(
        segments: List<Triple<Long, Double, String>>, // id, duration, text
        targetLanguage: String,
        style: String,
        engine: String = "gemini"
    ): List<TranslateItem> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        when (engine.lowercase()) {
            "sealion" -> translateWithSeaLion(segments, targetLanguage, style)
            "custom" -> translateWithCustomModel(segments, targetLanguage, style)
            else -> translateWithGemini(segments, targetLanguage, style)
        }
    }

    private suspend fun translateWithGemini(
        segments: List<Triple<Long, Double, String>>,
        targetLanguage: String,
        style: String
    ): List<TranslateItem> {
        val apiKey = geminiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw IOException("Gemini API key is required. Please set it in Settings.")
        }

        val inputJsonArray = JSONArray()
        for ((id, duration, text) in segments) {
            inputJsonArray.put(JSONObject().apply {
                put("id", id)
                put("duration", duration)
                put("text", text)
            })
        }

        val prompt = "You are a professional film dubbing translator. Translate each subtitle line into $targetLanguage. " +
                "Style: $style. Keep it short enough to be spoken naturally within the given duration (seconds). " +
                "Keep character names and tone consistent. Return ONLY a JSON array of objects: [{\"id\": number, \"translation\": string}] matching the provided ids.\n\n" +
                inputJsonArray.toString()

        val requestJson = JSONObject().apply {
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()
            partsArray.put(JSONObject().apply { put("text", prompt) })
            contentObj.put("role", "user")
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            put("contents", contentsArray)

            // No temperature/topP/topK: Gemini 3.6+ ignores them (future models reject them).
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val model = GeminiModels.resolve(geminiModelProvider())
        val url = GeminiModels.generateContentUrl(model, apiKey)
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw IOException("Gemini translation error ($model) ${response.code}: $responseBody")
        }

        val textContent = extractCandidateText(responseBody)
        return parseTranslateItems(textContent)
    }

    private suspend fun translateWithSeaLion(
        segments: List<Triple<Long, Double, String>>,
        targetLanguage: String,
        style: String
    ): List<TranslateItem> {
        val sealionKey = sealionKeyProvider().trim()
        if (sealionKey.isEmpty()) {
            throw IOException("SeaLion API key is required. Please set it in Settings.")
        }

        val model = SeaLionModels.resolve(sealionModelProvider())
        val rawUrl = sealionUrlProvider().trim().ifBlank { "https://api.sea-lion.ai/v1/" }
        val endpoint = if (rawUrl.endsWith("/chat/completions")) rawUrl else "${rawUrl.removeSuffix("/")}/chat/completions"

        return executeOpenAiChatCompletion(
            endpointUrl = endpoint,
            apiKey = sealionKey,
            modelName = model,
            segments = segments,
            targetLanguage = targetLanguage,
            style = style
        )
    }

    private suspend fun translateWithCustomModel(
        segments: List<Triple<Long, Double, String>>,
        targetLanguage: String,
        style: String
    ): List<TranslateItem> {
        val (_, customUrl, customKey, customModel) = customConfigProvider()
        val cleanUrl = customUrl.trim()
        val cleanKey = customKey.trim()
        val cleanModel = customModel.trim().ifBlank { "custom-model" }

        if (cleanUrl.isEmpty()) {
            throw IOException("Custom API URL is required. Please set it in Settings.")
        }

        val endpoint = if (cleanUrl.endsWith("/chat/completions")) {
            cleanUrl
        } else {
            "${cleanUrl.removeSuffix("/")}/chat/completions"
        }

        return executeOpenAiChatCompletion(
            endpointUrl = endpoint,
            apiKey = cleanKey,
            modelName = cleanModel,
            segments = segments,
            targetLanguage = targetLanguage,
            style = style
        )
    }

    private fun executeOpenAiChatCompletion(
        endpointUrl: String,
        apiKey: String,
        modelName: String,
        segments: List<Triple<Long, Double, String>>,
        targetLanguage: String,
        style: String
    ): List<TranslateItem> {
        val inputJsonArray = JSONArray()
        for ((id, duration, text) in segments) {
            inputJsonArray.put(JSONObject().apply {
                put("id", id)
                put("duration", duration)
                put("text", text)
            })
        }

        val prompt = "You are a professional film dubbing translator. Translate each subtitle line into $targetLanguage. " +
                "Style: $style. Keep character names consistent. Return ONLY a JSON array of objects: [{\"id\": number, \"translation\": string}].\n\n" +
                inputJsonArray.toString()

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "You are a subtitle localization engine. Output strictly raw JSON array with id and translation.")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val requestPayload = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("temperature", 0.3)
        }

        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(requestPayload.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(reqBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val hint = if (responseBody.contains("Connection error", ignoreCase = true) || response.code == 500) {
                "\n\nHint: Model '$modelName' may be offline on the server. Please switch to '${SeaLionModels.DEFAULT}' in Settings."
            } else ""
            throw IOException("Translation API error (${modelName}) ${response.code}: $responseBody$hint")
        }

        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
        val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""

        return parseTranslateItems(content)
    }

    private fun extractCandidateText(jsonString: String): String {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val first = candidates.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseTranslateItems(jsonText: String): List<TranslateItem> {
        val trimmed = jsonText.trim()
        val arrayString = when {
            trimmed.startsWith("[") -> trimmed
            trimmed.contains("[") && trimmed.contains("]") -> {
                val start = trimmed.indexOf("[")
                val end = trimmed.lastIndexOf("]")
                trimmed.substring(start, end + 1)
            }
            else -> return emptyList()
        }

        val jsonArray = JSONArray(arrayString)
        val list = mutableListOf<TranslateItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val id = obj.optLong("id", -1L)
            val translation = obj.optString("translation", "").trim()
            if (id != -1L && translation.isNotEmpty()) {
                list.add(TranslateItem(id = id, translation = translation))
            }
        }
        return list
    }
}
