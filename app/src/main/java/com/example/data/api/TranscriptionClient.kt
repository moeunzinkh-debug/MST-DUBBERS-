package com.example.data.api

import android.util.Base64
import com.example.media.WhisperAudioChunker
import com.example.media.WhisperSegmentCleaner
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
 *
 * The Groq Whisper path follows TurboScribe's workflow: the requested audio language is passed
 * to the model (so a Khmer dub is not auto-detected as Chinese), and the audio is sliced into
 * speech blocks at silent scenes before upload, which keeps segment timestamps locked onto the
 * actual dialogue instead of stretching across pauses and intro music.
 */
class TranscriptionClient(
    private val geminiKeyProvider: () -> String,
    private val groqKeyProvider: () -> String,
    private val geminiModelProvider: () -> String = { GeminiModels.DEFAULT },
    geminiApiBaseUrl: String = DEFAULT_GEMINI_API_ROOT,
    private val client: OkHttpClient = buildDefaultTranscriptionHttpClient(),
    private val audioTrackExtractor: (File, String?) -> File = { file, language ->
        AudioTrackExtractor.extractAudioTrack(file, language)
    },
    private val groqLanguageProvider: () -> String = { WhisperLanguages.AUTO },
    private val whisperChunker: (File, String?) -> List<WhisperAudioChunker.Chunk>? = { file, language ->
        WhisperAudioChunker.chunkAudio(file, language)
    }
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
            val audioFile = audioTrackExtractor(videoFile, null)
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

        // TurboScribe's "Audio Language" setting: forcing the spoken language stops Whisper's
        // auto-detection from picking the wrong language (e.g. hearing a Khmer dub as Chinese).
        val language = WhisperLanguages.normalize(groqLanguageProvider())

        // TurboScribe-style pipeline: slice the audio at silent scenes and transcribe each
        // speech block separately. Whisper stretches segments across silence when it is handed
        // one long file (a line spoken at 00:21 comes back as 00:07–00:23), so the silence
        // never reaches the model in the first place here.
        val chunks = try {
            whisperChunker(videoFile, language)
        } catch (_: Throwable) {
            null
        }
        if (!chunks.isNullOrEmpty()) {
            try {
                return transcribeGroqChunks(chunks, groqKey, language)
            } finally {
                chunks.forEach { it.wavFile.delete() }
            }
        }

        // Fallback: the media codecs could not slice this file (or found no speech), so keep
        // the classic single upload — still with the language hint.
        return transcribeWithGroqSingleUpload(videoFile, groqKey, language)
    }

    /** Transcribes VAD-sliced speech blocks and reassembles TurboScribe-grade timestamps. */
    private suspend fun transcribeGroqChunks(
        chunks: List<WhisperAudioChunker.Chunk>,
        groqKey: String,
        language: String?
    ): List<TranscribeItem> {
        val cleanedSegments = mutableListOf<WhisperSegmentCleaner.TimedText>()

        chunks.forEachIndexed { index, chunk ->
            val rawSegments = executeGroqTranscriptionRequest(
                uploadFile = chunk.wavFile,
                uploadMediaType = "audio/wav",
                groqKey = groqKey,
                language = language
            )
            for (segment in rawSegments) {
                // Whisper reports block-relative times, just like the block's speech
                // regions. Snap edges that leaked into the padded silence back onto the
                // detected speech (and drop anything invented over silence), then move
                // the whole line onto the original video timeline.
                val relative = WhisperSegmentCleaner.TimedText(
                    startSec = segment.startSec,
                    endSec = segment.endSec,
                    text = segment.text
                )
                val clamped = WhisperSegmentCleaner.clampToSpeechRegions(relative, chunk.regions)
                    ?: continue
                cleanedSegments += clamped.copy(
                    startSec = clamped.startSec + chunk.startSec,
                    endSec = clamped.endSec + chunk.startSec
                )
            }
            if (index < chunks.lastIndex) {
                // Whisper endpoints are rate-limited per minute; a small pause between chunk
                // uploads keeps longer videos inside the free-tier budget.
                delay(GROQ_INTER_CHUNK_DELAY_MS)
            }
        }

        val lines = WhisperSegmentCleaner.coalesceFragments(
            WhisperSegmentCleaner.removeHallucinations(cleanedSegments)
        )
        return lines.mapIndexed { index, line ->
            TranscribeItem(
                start = line.startSec,
                end = line.endSec,
                text = line.text,
                gender = if (index % 2 == 1) "male" else "female"
            )
        }
    }

    /**
     * Legacy single-request Groq path, used when the audio could not be decoded and sliced
     * on-device. The video's audio track is ALWAYS remuxed out first so that only the audio —
     * never the high-bitrate video frames — is uploaded, whatever the file size; the original
     * video is only sent when remuxing itself fails (unsupported container on this device).
     */
    private suspend fun transcribeWithGroqSingleUpload(
        videoFile: File,
        groqKey: String,
        language: String?
    ): List<TranscribeItem> {
        var extractedAudioFile: File? = null
        val uploadFile = try {
            val extracted = audioTrackExtractor(videoFile, language)
            if (extracted != videoFile) {
                extractedAudioFile = extracted
                extracted
            } else {
                // The extractor passed the video through untouched (nothing to remux).
                videoFile
            }
        } catch (_: Throwable) {
            // Remuxing failed (unsupported container, codec error, ...); sending the
            // original file still beats failing the transcription outright.
            videoFile
        }
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
            val rawSegments = executeGroqTranscriptionRequest(
                uploadFile = uploadFile,
                uploadMediaType = uploadMediaType,
                groqKey = groqKey,
                language = language
            )
            return rawSegments
                .mapIndexed { index, segment ->
                    TranscribeItem(
                        start = segment.startSec,
                        end = maxOf(segment.endSec, segment.startSec + 0.5),
                        text = segment.text,
                        gender = if (index % 2 == 1) "male" else "female"
                    )
                }
                .sortedBy { it.start }
        } finally {
            // Never delete the user's original video, only the temporary audio file.
            extractedAudioFile?.delete()
        }
    }

    /** One multipart transcription request, retried on rate limits and server hiccups. */
    private suspend fun executeGroqTranscriptionRequest(
        uploadFile: File,
        uploadMediaType: String,
        groqKey: String,
        language: String?
    ): List<GroqRawSegment> {
        var attempt = 0
        var lastError = ""
        while (true) {
            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    uploadFile.name,
                    uploadFile.asRequestBody(uploadMediaType.toMediaType())
                )
                .addFormDataPart("model", GROQ_MODEL)
                .addFormDataPart("response_format", "verbose_json")
                .apply {
                    if (language != null) addFormDataPart("language", language)
                }
                .build()

            val request = Request.Builder()
                .url(GROQ_TRANSCRIPTION_URL)
                .header("Authorization", "Bearer $groqKey")
                .post(requestBody)
                .build()

            var retryAfterSec: Long? = null
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> return parseGroqVerboseSegments(responseBody)
                    response.code == 429 || response.code in 500..599 -> {
                        retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull()
                        lastError = "Groq Whisper error ${response.code}: $responseBody"
                    }
                    else -> throw IOException("Groq Whisper error ${response.code}: $responseBody")
                }
            }

            attempt++
            if (attempt >= MAX_GROQ_ATTEMPTS) {
                throw IOException("$lastError (gave up after $attempt attempts)")
            }
            val backoffSec = (
                retryAfterSec ?: GROQ_RETRY_BACKOFF_SEC[
                    minOf(attempt - 1, GROQ_RETRY_BACKOFF_SEC.lastIndex)
                ]
                ).coerceIn(0, MAX_GROQ_RETRY_WAIT_SEC)
            delay(backoffSec * 1000L)
        }
    }

    /** Raw Whisper segments of one uploaded block, exactly as the API reports them. */
    private data class GroqRawSegment(val startSec: Double, val endSec: Double, val text: String)

    private fun parseGroqVerboseSegments(jsonString: String): List<GroqRawSegment> {
        val root = JSONObject(jsonString)
        val segmentsArray = root.optJSONArray("segments") ?: return emptyList()
        val list = mutableListOf<GroqRawSegment>()
        for (i in 0 until segmentsArray.length()) {
            val seg = segmentsArray.getJSONObject(i)
            val start = seg.optDouble("start", 0.0)
            val end = seg.optDouble("end", start + 2.0)
            val text = seg.optString("text", "").trim()
            if (text.isNotEmpty()) {
                list.add(
                    GroqRawSegment(
                        startSec = start,
                        endSec = maxOf(end, start + WhisperSegmentCleaner.MIN_SEGMENT_SEC),
                        text = text
                    )
                )
            }
        }
        return list
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
        private const val GROQ_MODEL = "whisper-large-v3"
        private const val MAX_GROQ_ATTEMPTS = 3
        private const val GROQ_INTER_CHUNK_DELAY_MS = 400L
        private const val MAX_GROQ_RETRY_WAIT_SEC = 30L
        private val GROQ_RETRY_BACKOFF_SEC = longArrayOf(4L, 12L)
        private const val TRANSCRIPTION_PROMPT =
            "Transcribe all spoken dialogue in the provided audio or video into subtitle segments. " +
                "Return ONLY a JSON array of objects: [{\"start\": seconds(number), \"end\": seconds(number), " +
                "\"text\": string, \"gender\": \"female\"|\"male\"}]. " +
                "Keep each segment between 1 and 7 seconds, in the original spoken language, with accurate timestamps."
    }
}
