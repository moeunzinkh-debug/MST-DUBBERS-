package com.example.data.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val DEFAULT_GEMINI_API_ROOT = "https://generativelanguage.googleapis.com/"

private fun buildDefaultTranscriptionHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(90, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.MINUTES)
    .writeTimeout(30, TimeUnit.MINUTES)
    .build()

/**
 * Transcribes local videos using Gemini or Groq Whisper.
 *
 * Gemini receives the video's compressed audio track instead of its high-bitrate video frames.
 * Small audio tracks are sent inline; larger ones use Gemini's resumable Files API and stream from
 * disk, avoiding loading hundreds of megabytes into memory.
 */
class TranscriptionClient(
    private val geminiKeyProvider: () -> String,
    private val groqKeyProvider: () -> String,
    private val geminiModelProvider: () -> String = { GeminiModels.DEFAULT },
    geminiApiBaseUrl: String = DEFAULT_GEMINI_API_ROOT,
    private val client: OkHttpClient = buildDefaultTranscriptionHttpClient(),
    private val audioTrackExtractor: (File) -> File = { AudioTrackExtractor.extractAudioTrack(it) }
) {
    private val geminiApiRoot: HttpUrl = (geminiApiBaseUrl.trimEnd('/') + "/").toHttpUrl()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribe(
        videoFile: File,
        mimeType: String = "video/mp4",
        engine: String = "gemini"
    ): List<TranscribeItem> = withContext(Dispatchers.IO) {
        if (!videoFile.isFile || !videoFile.exists()) {
            throw IOException("Video file does not exist on disk")
        }
        if (videoFile.length() <= 0L) {
            throw IOException("Video file is empty")
        }

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

        // Gemini can transcribe audio directly. Sending only the compressed audio track greatly
        // reduces upload size and avoids spending time uploading high-bitrate video frames.
        val media = extractAudioForGemini(videoFile, mimeType)
        try {
            val size = media.file.length()
            if (size <= 0L) throw IOException("No audio data was found in this video")
            if (size > MAX_GEMINI_FILE_SIZE_BYTES) {
                throw IOException("Gemini supports media files up to 2 GB. This transcription file is too large.")
            }

            val uploadedFile = if (size > MAX_INLINE_MEDIA_BYTES) {
                uploadGeminiFile(apiKey, media.file, media.mimeType)
            } else {
                null
            }

            try {
                if (uploadedFile != null) {
                    waitForGeminiFile(apiKey, uploadedFile)
                }

                val model = GeminiModels.resolve(geminiModelProvider())
                val requestJson = buildTranscriptionRequest(media.file, media.mimeType, uploadedFile)
                val request = Request.Builder()
                    .url(geminiUrl("v1beta/models/$model:generateContent", apiKey))
                    .post(requestJson.toString().toRequestBody(jsonMediaType))
                    .build()

                return client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(
                            "Gemini error ($model) ${response.code}: ${apiErrorMessage(responseBody) ?: responseBody}"
                        )
                    }
                    parseTranscribeJson(extractCandidateText(responseBody))
                }
            } finally {
                // Uploaded Files API objects count against the user's storage quota. Remove them as
                // soon as transcription finishes; Gemini also expires files automatically after 48 hours.
                if (uploadedFile != null) {
                    deleteGeminiFile(apiKey, uploadedFile.name)
                }
            }
        } finally {
            media.temporaryFile?.delete()
        }
    }

    private fun extractAudioForGemini(videoFile: File, videoMimeType: String): TranscriptionMediaSource {
        val originalMimeType = videoMimeType.trim().ifBlank { "video/mp4" }
        return try {
            val audioFile = audioTrackExtractor(videoFile)
            if (audioFile == videoFile) {
                TranscriptionMediaSource(videoFile, originalMimeType)
            } else {
                TranscriptionMediaSource(audioFile, "audio/mp4", temporaryFile = audioFile)
            }
        } catch (_: Exception) {
            // Keep transcription working for containers/codecs Android cannot remux; Gemini can
            // still process the original video through the Files API.
            TranscriptionMediaSource(videoFile, originalMimeType)
        }
    }

    private fun buildTranscriptionRequest(
        mediaFile: File,
        mimeType: String,
        uploadedFile: UploadedGeminiFile?
    ): JSONObject {
        val mediaPart = if (uploadedFile != null) {
            JSONObject().apply {
                put("fileData", JSONObject().apply {
                    put("mimeType", mimeType)
                    put("fileUri", uploadedFile.uri)
                })
            }
        } else {
            val base64Media = mediaFile.inputStream().use { stream ->
                Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
            }
            JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", base64Media)
                })
            }
        }

        val parts = JSONArray()
            .put(mediaPart)
            .put(JSONObject().put("text", TRANSCRIPTION_PROMPT))

        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }
    }

    /** Starts a resumable Gemini upload and streams [mediaFile] directly to its upload URL. */
    private fun uploadGeminiFile(
        apiKey: String,
        mediaFile: File,
        mimeType: String
    ): UploadedGeminiFile {
        val startRequest = Request.Builder()
            .url(geminiUrl("upload/v1beta/files", apiKey))
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", mediaFile.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(
                JSONObject().put("file", JSONObject().put("display_name", mediaFile.name))
                    .toString().toRequestBody(jsonMediaType)
            )
            .build()

        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "Gemini upload could not be started (${response.code}): " +
                        (apiErrorMessage(responseBody) ?: responseBody)
                )
            }
            response.header("X-Goog-Upload-URL")
                ?: throw IOException("Gemini did not return a resumable upload URL")
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(mediaFile.asRequestBody(mimeType.toMediaType()))
            .build()

        return client.newCall(uploadRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "Gemini media upload failed (${response.code}): " +
                        (apiErrorMessage(responseBody) ?: responseBody)
                )
            }

            val root = JSONObject(responseBody)
            val file = root.optJSONObject("file") ?: root
            val name = file.optString("name").trim()
            if (name.isEmpty()) {
                throw IOException("Gemini uploaded the media but returned no file identifier")
            }
            val uri = file.optString("uri").trim().ifBlank {
                geminiApiRoot.newBuilder().addPathSegments("v1beta/$name").build().toString()
            }
            val state = file.optString("state", "PROCESSING").uppercase(Locale.US)
            UploadedGeminiFile(name = name, uri = uri, state = state)
        }
    }

    /** Waits until Gemini has finished preparing the uploaded media before referencing it. */
    private suspend fun waitForGeminiFile(apiKey: String, file: UploadedGeminiFile) {
        var state = file.state.ifBlank { "ACTIVE" }
        val deadline = System.currentTimeMillis() + FILE_PROCESSING_TIMEOUT_MS

        while (state != "ACTIVE") {
            if (state == "FAILED") {
                throw IOException("Gemini could not process this media file. Try another format or use Groq Whisper.")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("Gemini is still processing this video. Please try transcribing it again later.")
            }

            delay(FILE_PROCESSING_POLL_INTERVAL_MS)
            val request = Request.Builder()
                .url(geminiUrl("v1beta/${file.name}", apiKey))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(
                        "Could not check Gemini media processing status (${response.code}): " +
                            (apiErrorMessage(responseBody) ?: responseBody)
                    )
                }
                val root = JSONObject(responseBody)
                val fileJson = root.optJSONObject("file") ?: root
                state = fileJson.optString("state", "ACTIVE").uppercase(Locale.US)
            }
        }
    }

    private fun deleteGeminiFile(apiKey: String, fileName: String) {
        try {
            val request = Request.Builder()
                .url(geminiUrl("v1beta/$fileName", apiKey))
                .delete()
                .build()
            client.newCall(request).execute().use { /* best-effort cleanup */ }
        } catch (_: Exception) {
            // Gemini expires uploaded files automatically, so cleanup failures are safe to ignore.
        }
    }

    private fun geminiUrl(path: String, apiKey: String): HttpUrl =
        geminiApiRoot.newBuilder()
            .addPathSegments(path)
            .addQueryParameter("key", apiKey)
            .build()

    private suspend fun transcribeWithGroq(videoFile: File): List<TranscribeItem> {
        // Keys copied from settings/apps sometimes contain embedded line breaks. Groq API keys are
        // printable ASCII tokens; strip whitespace/control characters before putting one in a header.
        val groqKey = groqKeyProvider().filter { it.code in 0x21..0x7E }
        if (groqKey.isEmpty()) {
            throw IOException("Groq API key is required for Whisper Large v3. Please set it in Settings.")
        }

        // Keep the previous direct upload for small clips. For high-bitrate/large videos, remux
        // only the audio track so the video frames do not count against Groq's 25 MB request limit.
        val extractedAudioFile = if (videoFile.length() > MAX_GROQ_AUDIO_FILE_BYTES) {
            audioTrackExtractor(videoFile)
        } else {
            null
        }
        val uploadFile = extractedAudioFile ?: videoFile
        try {
            if (uploadFile.length() > MAX_GROQ_AUDIO_FILE_BYTES) {
                val megabytes = uploadFile.length() / (1024.0 * 1024.0)
                val description = if (extractedAudioFile != null) "the extracted audio" else "the video"
                throw IOException(
                    "Groq Whisper accepts files up to 25 MB; $description is %.1f MB. "
                        .format(Locale.US, megabytes) +
                        "Choose Gemini for videos up to 2 GB or use a shorter clip."
                )
            }

            val uploadMediaType = if (extractedAudioFile != null) "audio/mp4" else "application/octet-stream"
            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    uploadFile.name,
                    uploadFile.asRequestBody(uploadMediaType.toMediaType())
                )
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("response_format", "verbose_json")
                .build()

            val request = Request.Builder()
                .url(GROQ_TRANSCRIPTION_URL)
                .header("Authorization", "Bearer $groqKey")
                .post(requestBody)
                .build()

            return client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Groq Whisper error ${response.code}: $responseBody")
                }
                parseGroqSegments(responseBody)
            }
        } finally {
            extractedAudioFile?.delete()
        }
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
        } catch (_: Exception) {
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

    private fun apiErrorMessage(responseBody: String): String? = try {
        JSONObject(responseBody).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private data class UploadedGeminiFile(
        val name: String,
        val uri: String,
        val state: String
    )

    private data class TranscriptionMediaSource(
        val file: File,
        val mimeType: String,
        val temporaryFile: File? = null
    )

    companion object {
        /** Gemini Files API allows an individual media file up to 2 GB. */
        const val MAX_GEMINI_FILE_SIZE_BYTES: Long = 2L * 1024L * 1024L * 1024L

        /** Stay safely under the 20 MB request limit after base64 expansion and JSON overhead. */
        const val MAX_INLINE_MEDIA_BYTES: Long = 14L * 1024L * 1024L

        // Leave room below Groq's 25 MB request cap for multipart headers and form fields.
        private const val MAX_GROQ_AUDIO_FILE_BYTES = 24_000_000L
        private const val FILE_PROCESSING_TIMEOUT_MS = 15 * 60 * 1000L
        private const val FILE_PROCESSING_POLL_INTERVAL_MS = 2_000L
        private const val GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val TRANSCRIPTION_PROMPT =
            "Transcribe all spoken dialogue in the provided audio or video into subtitle segments. " +
                "Return ONLY a JSON array of objects: [{\"start\": seconds(number), \"end\": seconds(number), " +
                "\"text\": string, \"gender\": \"female\"|\"male\"}]. " +
                "Keep each segment between 1 and 7 seconds, in the original spoken language, with accurate timestamps."
    }
}
