package com.example.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class EdgeTtsClient(
    private val serverUrlProvider: () -> String = { "https://takumi0002-tts-kh.hf.space" }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateSpeech(
        text: String,
        voice: String,
        speed: Float,
        destFile: File
    ): Double = withContext(Dispatchers.IO) {
        val baseUrl = serverUrlProvider().trim().removeSuffix("/")
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            throw IOException("Text to speak cannot be empty")
        }

        val ratePercent = if (speed >= 1.0f) {
            "+${((speed - 1.0f) * 100).toInt()}%"
        } else {
            "-${((1.0f - speed) * 100).toInt()}%"
        }

        // Strategy 1: User's direct HF Edge TTS server: POST /api/process-multi
        var audioBytes = tryTakumiHfProcessMulti(baseUrl, cleanText, voice, speed)

        // Strategy 2: GET request with query parameters (standard edge-tts REST wrappers)
        if (audioBytes == null || audioBytes.isEmpty()) {
            audioBytes = tryGetAudio(baseUrl, cleanText, voice, ratePercent, speed)
        }

        // Strategy 3: POST JSON
        if (audioBytes == null || audioBytes.isEmpty()) {
            audioBytes = tryPostAudio(baseUrl, cleanText, voice, ratePercent, speed)
        }

        if (audioBytes == null || audioBytes.isEmpty()) {
            throw IOException("TTS server at $baseUrl did not return audio data.")
        }

        FileOutputStream(destFile).use { it.write(audioBytes) }

        // Determine audio duration using MediaMetadataRetriever
        var duration = 0.0
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(destFile.absolutePath)
            val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (durStr != null) {
                duration = durStr.toDouble() / 1000.0
            }
            retriever.release()
        } catch (e: Exception) {
            duration = (cleanText.length / 14.0) / speed.toDouble()
        }

        duration.coerceAtLeast(0.5)
    }

    /**
     * Direct integration with user's backend: https://takumi0002-tts-kh.hf.space/api/process-multi
     * Sends multipart form data: data=[{text, voiceName, speed, pitch}], autoSpeed=false
     */
    private fun tryTakumiHfProcessMulti(
        baseUrl: String,
        text: String,
        voice: String,
        speed: Float
    ): ByteArray? {
        return try {
            val voiceKey = when {
                voice.contains("sreymom", ignoreCase = true) || voice.contains("female", ignoreCase = true) -> "sreymom"
                voice.contains("piseth", ignoreCase = true) || voice.contains("male", ignoreCase = true) -> "piseth"
                else -> if (voice.isNotBlank()) voice else "piseth"
            }

            val partsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("text", text)
                    put("voiceName", voiceKey)
                    put("speed", speed)
                    put("pitch", 0)
                })
            }

            val formBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data", partsArray.toString())
                .addFormDataPart("autoSpeed", "false")
                .build()

            val targetUrl = if (baseUrl.endsWith("/api/process-multi")) baseUrl else "$baseUrl/api/process-multi"
            val request = Request.Builder()
                .url(targetUrl)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.size > 200) {
                    return bytes
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun tryGetAudio(
        baseUrl: String,
        text: String,
        voice: String,
        ratePercent: String,
        speed: Float
    ): ByteArray? {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val encodedVoice = URLEncoder.encode(voice, "UTF-8")
        val encodedRate = URLEncoder.encode(ratePercent, "UTF-8")

        val candidateUrls = listOf(
            "$baseUrl/tts?text=$encodedText&voice=$encodedVoice&rate=$encodedRate",
            "$baseUrl/api/tts?text=$encodedText&voice=$encodedVoice&rate=$encodedRate",
            "$baseUrl/tts?text=$encodedText&voice=$encodedVoice&speed=$speed",
            "$baseUrl/tts?text=$encodedText",
            "$baseUrl/api?text=$encodedText&voice=$encodedVoice"
        )

        for (url in candidateUrls) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.size > 200) {
                        return bytes
                    }
                }
            } catch (e: Exception) {
                // Try next endpoint
            }
        }
        return null
    }

    private fun tryPostAudio(
        baseUrl: String,
        text: String,
        voice: String,
        ratePercent: String,
        speed: Float
    ): ByteArray? {
        val postEndpoints = listOf(
            "$baseUrl/tts",
            "$baseUrl/api/tts",
            "$baseUrl/generate",
            "$baseUrl/predict"
        )

        val jsonBody = JSONObject().apply {
            put("text", text)
            put("voice", voice)
            put("rate", ratePercent)
            put("speed", speed)
        }.toString()

        for (url in postEndpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.size > 200) {
                        return bytes
                    }
                }
            } catch (e: Exception) {
                // Try next endpoint
            }
        }
        return null
    }
}
