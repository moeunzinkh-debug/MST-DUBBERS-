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
import java.io.FileInputStream
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
    private val apiKeyProvider: () -> String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribeVideo(
        videoFile: File,
        mimeType: String = "video/mp4"
    ): List<TranscribeItem> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw IOException("Gemini API key is required. Please add your key in Settings.")
        }

        // Check file size (keep within 19MB for direct inlineData payload)
        if (videoFile.length() > 19 * 1024 * 1024) {
            throw IOException("Video file is larger than 19MB. Please import a subtitle file (.srt/.vtt) or use a smaller clip.")
        }

        val base64Video = FileInputStream(videoFile).use { fis ->
            val bytes = fis.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        val requestJson = JSONObject().apply {
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()

            // Video inline data
            val videoPart = JSONObject().apply {
                val inlineData = JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", base64Video)
                }
                put("inlineData", inlineData)
            }
            partsArray.put(videoPart)

            // Prompt
            val textPart = JSONObject().apply {
                put("text", "Transcribe all spoken dialogue in this video into subtitle segments. " +
                        "Return ONLY a JSON array of objects: [{\"start\": seconds(number), \"end\": seconds(number), \"text\": string, \"gender\": \"female\"|\"male\"}]. " +
                        "Keep each segment between 1 and 7 seconds, in the original spoken language, with accurate timestamps. gender = the apparent gender of the speaker.")
            }
            partsArray.put(textPart)

            contentObj.put("role", "user")
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            put("contents", contentsArray)

            val genConfig = JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.4)
            }
            put("generationConfig", genConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val errorMsg = parseErrorMessage(responseBody) ?: "Gemini API error ${response.code}"
            throw IOException(errorMsg)
        }

        val textContent = parseCandidateText(responseBody)
        parseTranscribeItems(textContent)
    }

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

            val genConfig = JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
            }
            put("generationConfig", genConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val errorMsg = parseErrorMessage(responseBody) ?: "Gemini error ${response.code}"
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

    private fun parseTranscribeItems(jsonText: String): List<TranscribeItem> {
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
        val list = mutableListOf<TranscribeItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val start = obj.optDouble("start", 0.0)
            val end = obj.optDouble("end", start + 2.0)
            val text = obj.optString("text", "").trim()
            val gender = if (obj.optString("gender", "female").equals("male", ignoreCase = true)) "male" else "female"
            if (text.isNotEmpty()) {
                list.add(TranscribeItem(start = start, end = maxOf(end, start + 0.5), text = text, gender = gender))
            }
        }
        return list.sortedBy { it.start }
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
