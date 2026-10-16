package com.example.data.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranscriptionClient(
    private val geminiKeyProvider: () -> String,
    private val groqKeyProvider: () -> String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribe(
        videoFile: File,
        mimeType: String = "video/mp4",
        engine: String = "gemini"
    ): List<TranscribeItem> = withContext(Dispatchers.IO) {
        if (engine.equals("groq_whisper", ignoreCase = true)) {
            transcribeWithGroq(videoFile)
        } else {
            transcribeWithGemini(videoFile, mimeType)
        }
    }

    private suspend fun transcribeWithGemini(
        videoFile: File,
        mimeType: String
    ): List<TranscribeItem> {
        val apiKey = geminiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw IOException("Gemini API key is required. Please add it in Settings.")
        }
        if (videoFile.length() > 20 * 1024 * 1024) {
            throw IOException("Video is too large for direct Gemini inline transcription (>20MB). Consider using Groq Whisper or importing an .SRT file.")
        }

        val base64Video = FileInputStream(videoFile).use { fis ->
            val bytes = fis.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        val requestJson = JSONObject().apply {
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()

            partsArray.put(JSONObject().apply {
                val inlineData = JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", base64Video)
                }
                put("inlineData", inlineData)
            })

            partsArray.put(JSONObject().apply {
                put("text", "Transcribe all spoken dialogue in this video into subtitle segments. " +
                        "Return ONLY a JSON array of objects: [{\"start\": seconds(number), \"end\": seconds(number), \"text\": string, \"gender\": \"female\"|\"male\"}]. " +
                        "Keep each segment between 1 and 7 seconds, in the original spoken language, with accurate timestamps.")
            })

            contentObj.put("role", "user")
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            put("contents", contentsArray)

            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw IOException("Gemini error ${response.code}: $responseBody")
        }

        val textContent = extractCandidateText(responseBody)
        return parseTranscribeJson(textContent)
    }

    private suspend fun transcribeWithGroq(audioOrVideoFile: File): List<TranscribeItem> {
        val groqKey = groqKeyProvider().trim()
        if (groqKey.isEmpty()) {
            throw IOException("Groq API key is required for Whisper Large v3. Please set it in Settings.")
        }

        val url = "https://api.groq.com/openai/v1/audio/transcriptions"
        val fileBody = audioOrVideoFile.asRequestBody("application/octet-stream".toMediaType())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioOrVideoFile.name, fileBody)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $groqKey")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw IOException("Groq Whisper error ${response.code}: $responseBody")
        }

        return parseGroqSegments(responseBody)
    }

    private fun parseGroqSegments(jsonString: String): List<TranscribeItem> {
        val root = JSONObject(jsonString)
        val segmentsArray = root.optJSONArray("segments") ?: return emptyList()
        val list = mutableListOf<TranscribeItem>()

        for (i in 0 until segmentsArray.length()) {
            val seg = segmentsArray.getJSONObject(i)
            val start = seg.optDouble("start", 0.0)
            val end = seg.optDouble("end", start + 2.0)
            val text = seg.optString("text", "").trim()
            if (text.isNotEmpty()) {
                list.add(
                    TranscribeItem(
                        start = start,
                        end = maxOf(end, start + 0.5),
                        text = text,
                        gender = if (i % 2 == 1) "male" else "female"
                    )
                )
            }
        }
        return list.sortedBy { it.start }
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

    private fun parseTranscribeJson(jsonText: String): List<TranscribeItem> {
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
}
