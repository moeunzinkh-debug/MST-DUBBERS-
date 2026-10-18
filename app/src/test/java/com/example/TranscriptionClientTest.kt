package com.example

import com.example.data.api.TranscribeItem
import com.example.data.api.TranscriptionClient
import com.example.media.SpeechInterval
import com.example.media.WhisperAudioChunker
import android.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranscriptionClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Gemini videos over 500 MB use streamed Files API upload`() = runBlocking {
        val videoFile = temporaryFolder.newFile("large-video.mp4")
        val largeVideoSize = 500L * 1024L * 1024L + 1L
        RandomAccessFile(videoFile, "rw").use { it.setLength(largeVideoSize) }

        val seenRequests = mutableListOf<Request>()
        val generatedResponse = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "[{\"start\":0,\"end\":2,\"text\":\"Hello\",\"gender\":\"female\"}]"
                            )
                        )
                    )
                )
            )
        ).toString()

        val fakeHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                seenRequests += request

                val body = when (request.url.encodedPath) {
                    "/upload/v1beta/files" -> {
                        assertEquals("resumable", request.header("X-Goog-Upload-Protocol"))
                        assertEquals("start", request.header("X-Goog-Upload-Command"))
                        assertEquals(largeVideoSize.toString(), request.header("X-Goog-Upload-Header-Content-Length"))
                        assertEquals("video/mp4", request.header("X-Goog-Upload-Header-Content-Type"))
                        "{}"
                    }

                    "/upload/session" -> {
                        assertEquals("upload, finalize", request.header("X-Goog-Upload-Command"))
                        assertEquals("0", request.header("X-Goog-Upload-Offset"))
                        assertEquals(largeVideoSize, request.body?.contentLength() ?: -1L)
                        """{"file":{"name":"files/test-video","uri":"https://gemini.test/v1beta/files/test-video","state":"ACTIVE"}}"""
                    }

                    "/v1beta/models/gemini-3.8-flash:generateContent" -> {
                        val requestBuffer = Buffer()
                        request.body!!.writeTo(requestBuffer)
                        val requestJson = JSONObject(requestBuffer.readUtf8())
                        val videoPart = requestJson
                            .getJSONArray("contents")
                            .getJSONObject(0)
                            .getJSONArray("parts")
                            .getJSONObject(0)
                        assertFalse("Large videos should use fileData, not inlineData", videoPart.has("inlineData"))
                        assertEquals(
                            "https://gemini.test/v1beta/files/test-video",
                            videoPart.getJSONObject("fileData").getString("fileUri")
                        )
                        generatedResponse
                    }

                    "/v1beta/files/test-video" -> {
                        assertEquals("DELETE", request.method)
                        "{}"
                    }

                    else -> throw AssertionError("Unexpected request: ${request.method} ${request.url}")
                }

                val headers = if (request.url.encodedPath == "/upload/v1beta/files") {
                    Headers.Builder()
                        .add("X-Goog-Upload-URL", "https://gemini.test/upload/session")
                        .build()
                } else {
                    Headers.Builder().build()
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(headers)
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val transcriber = TranscriptionClient(
            geminiKeyProvider = { "test-gemini-key" },
            groqKeyProvider = { "test-groq-key" },
            geminiApiBaseUrl = "https://gemini.test/",
            client = fakeHttpClient,
            audioTrackExtractor = { file, _ -> file }
        )

        val items = transcriber.transcribe(videoFile, "video/mp4", "gemini")

        assertEquals(listOf(TranscribeItem(0.0, 2.0, "Hello", "female")), items)
        assertEquals(
            listOf(
                "/upload/v1beta/files",
                "/upload/session",
                "/v1beta/models/gemini-3.8-flash:generateContent",
                "/v1beta/files/test-video"
            ),
            seenRequests.map { it.url.encodedPath }
        )
        assertTrue(TranscriptionClient.MAX_GEMINI_FILE_SIZE_BYTES > largeVideoSize)
    }

    @Test
    fun `Gemini receives the extracted audio track instead of the video`() = runBlocking {
        val videoFile = temporaryFolder.newFile("video.mp4").apply { writeText("video frames") }
        val audioFile = temporaryFolder.newFile("audio.m4a").apply { writeText("audio track") }
        var requestCount = 0

        val fakeHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestCount++
                assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", request.url.encodedPath)

                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val requestJson = JSONObject(buffer.readUtf8())
                val mediaPart = requestJson
                    .getJSONArray("contents")
                    .getJSONObject(0)
                    .getJSONArray("parts")
                    .getJSONObject(0)
                assertFalse("Gemini should receive audio, not the original video", mediaPart.has("fileData"))
                val inlineAudio = mediaPart.getJSONObject("inlineData")
                assertEquals("audio/mp4", inlineAudio.getString("mimeType"))
                assertEquals(
                    "audio track",
                    String(Base64.decode(inlineAudio.getString("data"), Base64.DEFAULT), Charsets.UTF_8)
                )

                val transcriptText = "[{\"start\":0,\"end\":2,\"text\":\"Hello\",\"gender\":\"female\"}]"
                val responseBody = JSONObject().put(
                    "candidates",
                    JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", transcriptText))
                            )
                        )
                    )
                ).toString()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val transcriber = TranscriptionClient(
            geminiKeyProvider = { "test-gemini-key" },
            groqKeyProvider = { "test-groq-key" },
            client = fakeHttpClient,
            audioTrackExtractor = { _, _ -> audioFile }
        )

        assertEquals(
            listOf(TranscribeItem(0.0, 2.0, "Hello", "female")),
            transcriber.transcribe(videoFile, "video/mp4", "gemini")
        )
        assertEquals(1, requestCount)
        assertFalse("Temporary extracted audio should be removed", audioFile.exists())
    }

    @Test
    fun `Groq API key line breaks are removed before setting Authorization`() = runBlocking {
        val videoFile = temporaryFolder.newFile("groq-small.mp4").apply { writeText("video") }
        var authorizationHeader: String? = null

        val fakeHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                authorizationHeader = request.header("Authorization")
                val responseBody = """{"segments":[{"start":0,"end":1,"text":"Hello"}]}"""
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val transcriber = TranscriptionClient(
            geminiKeyProvider = { "test-gemini-key" },
            groqKeyProvider = { " gsk_test_\nkey\r\n " },
            client = fakeHttpClient
        )

        val items = transcriber.transcribe(videoFile, "video/mp4", "groq_whisper")

        assertEquals("Bearer gsk_test_key", authorizationHeader)
        assertFalse(authorizationHeader.orEmpty().contains('\n'))
        assertFalse(authorizationHeader.orEmpty().contains('\r'))
        assertEquals(listOf(TranscribeItem(0.0, 1.0, "Hello", "female")), items)
    }

    @Test
    fun `Groq chunked transcription sends language, offsets and clamps timestamps`() = runBlocking {
        val videoFile = temporaryFolder.newFile("video.mp4").apply { writeText("video") }
        val wav1 = temporaryFolder.newFile("chunk1.wav").apply { writeText("pcm-one") }
        val wav2 = temporaryFolder.newFile("chunk2.wav").apply { writeText("pcm-two") }
        val chunks = listOf(
            WhisperAudioChunker.Chunk(wav1, 3.75, listOf(SpeechInterval(0.25, 2.25))),
            WhisperAudioChunker.Chunk(wav2, 21.4, listOf(SpeechInterval(0.25, 1.5)))
        )
        val seenFilenames = mutableListOf<String>()
        var requestCount = 0

        val fakeHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestCount++
                assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", request.url.toString())
                assertEquals("Bearer gsk_test", request.header("Authorization"))

                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val body = buffer.readUtf8()
                assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-large-v3"))
                assertTrue(body.contains("name=\"response_format\"\r\n\r\nverbose_json"))
                assertTrue("The audio language must be forced like TurboScribe does", body.contains("name=\"language\"\r\n\r\nkm\r\n"))

                val responseBody = if (body.contains("filename=\"chunk1.wav\"")) {
                    seenFilenames += "chunk1.wav"
                    // First segment is stretched over the block's padding; the second one
                    // sits in silence and is a hallucination that must be dropped.
                    """{"segments":[{"start":0.0,"end":2.5,"text":"line one"},{"start":2.6,"end":3.4,"text":"♪"}]}"""
                } else {
                    seenFilenames += "chunk2.wav"
                    """{"segments":[{"start":0.1,"end":1.7,"text":"line two"}]}"""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val transcriber = TranscriptionClient(
            geminiKeyProvider = { "test-gemini-key" },
            groqKeyProvider = { " gsk_test " },
            client = fakeHttpClient,
            groqLanguageProvider = { "km" },
            whisperChunker = { _, _ -> chunks }
        )

        val items = transcriber.transcribe(videoFile, "video/mp4", "groq_whisper")

        assertEquals(2, requestCount)
        assertEquals(listOf("chunk1.wav", "chunk2.wav"), seenFilenames)
        // Whisper's [0.0, 2.5] on block 1 is clamped to its speech region [0.25, 2.25],
        // then offset by the block start 3.75 -> [4.0, 6.0] on the video timeline.
        assertEquals(
            listOf(
                TranscribeItem(4.0, 6.0, "line one", "female"),
                TranscribeItem(21.65, 22.9, "line two", "male")
            ),
            items
        )
        assertFalse("Chunk WAVs should be deleted after upload", wav1.exists() || wav2.exists())
    }

    @Test
    fun `Groq chunk requests retry on 429 and honor Retry-After`() = runBlocking {
        val videoFile = temporaryFolder.newFile("video.mp4").apply { writeText("video") }
        val wav = temporaryFolder.newFile("chunk.wav").apply { writeText("pcm") }
        val chunks = listOf(WhisperAudioChunker.Chunk(wav, 10.0, listOf(SpeechInterval(0.2, 1.0))))
        var requestCount = 0

        val fakeHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestCount++
                if (requestCount == 1) {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("Retry-After", "0")
                        .body("{\"error\":\"rate limited\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"segments":[{"start":0.2,"end":1.1,"text":"hello"}]}"""
                                .toResponseBody("application/json; charset=utf-8".toMediaType())
                        )
                        .build()
                }
            }
            .build()

        val transcriber = TranscriptionClient(
            geminiKeyProvider = { "test-gemini-key" },
            groqKeyProvider = { "gsk_test" },
            client = fakeHttpClient,
            whisperChunker = { _, _ -> chunks }
        )

        val items = transcriber.transcribe(videoFile, "video/mp4", "groq_whisper")

        assertEquals(2, requestCount)
        assertEquals(listOf(TranscribeItem(10.2, 11.0, "hello", "female")), items)
    }

    @Test
    fun `Groq fallback upload passes the language hint and extracts audio for large videos`() = runBlocking {
        val videoFile = temporaryFolder.newFile("big.mp4")
        RandomAccessFile(videoFile, "rw").use { it.setLength(24_000_001L) }
        val audioFile = temporaryFolder.newFile("audio.m4a").apply { writeText("audio track") }
        var extractorLanguage: String? = null
        var uploadBody = ""

        val fakeHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                uploadBody = buffer.readUtf8()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"segments":[{"start":0,"end":1,"text":"你好"}]}"""
                            .toResponseBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()
            }
            .build()

        val transcriber = TranscriptionClient(
            geminiKeyProvider = { "test-gemini-key" },
            groqKeyProvider = { "gsk_test" },
            client = fakeHttpClient,
            audioTrackExtractor = { _, language ->
                extractorLanguage = language
                audioFile
            },
            groqLanguageProvider = { "zh" },
            whisperChunker = { _, _ -> null }
        )

        val items = transcriber.transcribe(videoFile, "video/mp4", "groq_whisper")

        assertEquals("zh", extractorLanguage)
        assertTrue(uploadBody.contains("filename=\"audio.m4a\""))
        assertTrue(uploadBody.contains("name=\"language\"\r\n\r\nzh\r\n"))
        assertEquals(listOf(TranscribeItem(0.0, 1.0, "你好", "female")), items)
        assertFalse("Extracted audio should be removed", audioFile.exists())
    }

    @Test
    fun `Groq omits the language field on auto-detect`() = runBlocking {
        val videoFile = temporaryFolder.newFile("groq-auto.mp4").apply { writeText("video") }
        var uploadBody = ""

        val fakeHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                uploadBody = buffer.readUtf8()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"segments":[{"start":0,"end":1,"text":"Hello"}]}"""
                            .toResponseBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()
            }
            .build()

        val transcriber = TranscriptionClient(
            geminiKeyProvider = { "test-gemini-key" },
            groqKeyProvider = { "gsk_test" },
            client = fakeHttpClient,
            groqLanguageProvider = { "auto" },
            whisperChunker = { _, _ -> null }
        )

        transcriber.transcribe(videoFile, "video/mp4", "groq_whisper")

        assertFalse("Auto-detect must not send a language field", uploadBody.contains("name=\"language\""))
    }
}
