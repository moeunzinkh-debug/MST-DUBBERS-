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

    /**
     * Speaks [text] with the Edge TTS server and saves the audio (usually MP3) to [destFile].
     * Returns the audio duration in seconds.
     *
     * @throws TtsException with a [TtsException.Kind] telling whether trying again may work.
     */
    suspend fun generateSpeech(
        text: String,
        voice: String,
        speed: Float,
        destFile: File
    ): Double = withContext(Dispatchers.IO) {
        val baseUrl = serverUrlProvider().trim().removeSuffix("/")
        val cleanText = text.trim()
        if (!SpeakableText.hasWords(cleanText)) {
            throw TtsException(NO_WORDS_MESSAGE, TtsException.Kind.NOTHING_TO_SPEAK)
        }
        if (baseUrl.isEmpty()) {
            throw TtsException("No TTS server address. Set it in Settings.", TtsException.Kind.PERMANENT)
        }

        val ratePercent = if (speed >= 1.0f) {
            "+${((speed - 1.0f) * 100).toInt()}%"
        } else {
            "-${((1.0f - speed) * 100).toInt()}%"
        }

        // Strategy 1: the app's Edge TTS server (FastAPI): POST /api/process-multi
        val audioBytes = when (val primary = tryTakumiHfProcessMulti(baseUrl, cleanText, voice, speed)) {
            is Attempt.Audio -> primary.bytes
            is Attempt.Failed -> {
                // The server has this endpoint but couldn't make the audio: report why. Other
                // servers (404/405) may still offer one of the generic endpoints below.
                if (!primary.endpointMissing) throw primary.error
                tryGetAudio(baseUrl, cleanText, voice, ratePercent, speed)
                    ?: tryPostAudio(baseUrl, cleanText, voice, ratePercent, speed)
                    ?: throw TtsException(
                        "The TTS server at $baseUrl has no supported speech endpoint (${primary.error.message})",
                        TtsException.Kind.PERMANENT
                    )
            }
        }

        FileOutputStream(destFile).use { it.write(audioBytes) }
        audioDurationSec(destFile) ?: ((cleanText.length / 14.0) / speed.toDouble()).coerceAtLeast(0.5)
    }

    private fun audioDurationSec(file: File): Double? {
        var retriever: android.media.MediaMetadataRetriever? = null
        return try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()
                ?.let { (it / 1000.0).coerceAtLeast(0.5) }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private sealed class Attempt {
        class Audio(val bytes: ByteArray) : Attempt()

        /** [endpointMissing]: the server doesn't have this endpoint at all (HTTP 404/405). */
        class Failed(val error: TtsException, val endpointMissing: Boolean = false) : Attempt()
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
    ): Attempt {
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
        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .post(formBody)
                .build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                when {
                    response.isSuccessful && bytes.size > MIN_AUDIO_BYTES -> Attempt.Audio(bytes)
                    response.isSuccessful -> Attempt.Failed(
                        TtsException("The voice server sent no audio (${bytes.size} bytes)", TtsException.Kind.TEMPORARY)
                    )
                    response.code == 404 || response.code == 405 -> Attempt.Failed(
                        TtsException.forHttpStatus(response.code, errorDetail(bytes)),
                        endpointMissing = true
                    )
                    response.code == 400 && errorDetail(bytes).contains("No audio generated", ignoreCase = true) ->
                        // The line has words (checked before sending), so Edge TTS itself failed
                        // to answer, even after the server's own retries. Usually brief.
                        Attempt.Failed(
                            TtsException(
                                "Edge TTS gave no audio for this line (Microsoft's voice service is busy, try again)",
                                TtsException.Kind.TEMPORARY
                            )
                        )
                    else -> Attempt.Failed(
                        TtsException.forHttpStatus(response.code, errorDetail(bytes), retryAfterSec(response.header("Retry-After")))
                    )
                }
            }
        } catch (e: Exception) {
            Attempt.Failed(TtsException.from(e))
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
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.size > MIN_AUDIO_BYTES && !looksLikeText(bytes)) {
                            return bytes
                        }
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
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.size > MIN_AUDIO_BYTES && !looksLikeText(bytes)) {
                            return bytes
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next endpoint
            }
        }
        return null
    }

    companion object {
        const val NO_WORDS_MESSAGE = "No words to read (only symbols such as ♪ or ...)"

        /** Smaller answers can't be real audio. */
        private const val MIN_AUDIO_BYTES = 200

        /** The `detail` of a FastAPI error (`{"detail": "..."}`), or the start of the body. */
        internal fun errorDetail(body: ByteArray): String {
            val text = String(body, Charsets.UTF_8).trim()
            if (text.isEmpty()) return ""
            val detail = try {
                JSONObject(text).opt("detail")?.let { if (it is String) it else it.toString() }
            } catch (e: Exception) {
                null
            }
            return (detail ?: text).replace(Regex("\\s+"), " ").take(160)
        }

        /** Seconds from a `Retry-After` header (only the number-of-seconds form). */
        internal fun retryAfterSec(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

        /** HTML or JSON pages (e.g. a web page served for an unknown path) are not audio. */
        private fun looksLikeText(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8).trimStart().lowercase()
            return head.startsWith("<") || head.startsWith("{") || head.startsWith("[")
        }
    }
}
