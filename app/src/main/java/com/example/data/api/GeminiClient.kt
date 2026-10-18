package com.example.data.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TranscribeItem(
    val start: Double,
    val end: Double,
    val text: String,
    val gender: String
)

data class TranslateItem(
    val id: Long,
    val translation: String
)

class GeminiClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { GeminiModels.DEFAULT }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val transcriptionClient by lazy {
        TranscriptionClient(
            geminiKeyProvider = apiKeyProvider,
            groqKeyProvider = { "" },
            geminiModelProvider = modelProvider
        )
    }

    /** Uses the shared large-file upload path for Gemini video transcription. */
    suspend fun transcribeVideo(
        videoFile: File,
        mimeType: String = "video/mp4"
    ): List<TranscribeItem> = transcriptionClient.transcribe(videoFile, mimeType, engine = "gemini")

    suspend fun translateSegments(
        segments: List<Triple<Long, Double, String>>, // id, duration, text
        targetLanguage: String,
        style: String
    ): List<TranslateItem> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw IOException("Gemini API key is required. Please set it in Settings.")
        }
        if (segments.isEmpty()) return@withContext emptyList()

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
            val genConfig = JSONObject().apply {
                put("responseMimeType", "application/json")
            }
            put("generationConfig", genConfig)
        }

        val model = GeminiModels.resolve(modelProvider())
        val url = GeminiModels.generateContentUrl(model, apiKey)
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val errorMsg = parseErrorMessage(responseBody) ?: "Gemini error ($model) ${response.code}"
            throw IOException(errorMsg)
        }

        val textContent = parseCandidateText(responseBody)
        parseTranslateItems(textContent)
    }

    suspend fun generateSpeech(
        text: String,
        voiceName: String,
        stylePrompt: String = "Say expressively, like a movie dub"
    ): ByteArray = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw IOException("Gemini API key is required. Please configure it in Settings.")
        }

        val formattedPrompt = if (stylePrompt.isNotBlank()) "$stylePrompt: $text" else text

        val requestJson = JSONObject().apply {
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()
            partsArray.put(JSONObject().apply { put("text", formattedPrompt) })
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            put("contents", contentsArray)

            val genConfig = JSONObject().apply {
                val modalities = JSONArray().apply { put("AUDIO") }
                put("responseModalities", modalities)

                val speechConfig = JSONObject().apply {
                    val voiceConfig = JSONObject().apply {
                        val prebuiltVoiceConfig = JSONObject().apply {
                            put("voiceName", voiceName)
                        }
                        put("prebuiltVoiceConfig", prebuiltVoiceConfig)
                    }
                    put("voiceConfig", voiceConfig)
                }
                put("speechConfig", speechConfig)
            }
            put("generationConfig", genConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val errorMsg = parseErrorMessage(responseBody) ?: "Gemini TTS error ${response.code}"
            throw IOException(errorMsg)
        }

        parseAudioData(responseBody)
    }

    private fun parseCandidateText(jsonString: String): String {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val first = candidates.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                sb.append(p.optString("text", ""))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseAudioData(jsonString: String): ByteArray {
        val root = JSONObject(jsonString)
        val candidates = root.optJSONArray("candidates")
            ?: throw IOException("No audio returned from Gemini TTS")
        if (candidates.length() == 0) throw IOException("No candidate audio available")
        val content = candidates.getJSONObject(0).optJSONObject("content")
            ?: throw IOException("No content in TTS response")
        val parts = content.optJSONArray("parts")
            ?: throw IOException("No parts in TTS response")

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val inlineData = part.optJSONObject("inlineData")
            if (inlineData != null) {
                val data = inlineData.optString("data", "")
                if (data.isNotEmpty()) {
                    return Base64.decode(data, Base64.DEFAULT)
                }
            }
        }
        throw IOException("No audio stream found in TTS response")
    }

    private fun parseErrorMessage(jsonString: String): String? {
        return try {
            val root = JSONObject(jsonString)
            root.optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
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
