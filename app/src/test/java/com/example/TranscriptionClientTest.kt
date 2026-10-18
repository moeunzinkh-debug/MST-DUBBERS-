package com.example

import com.example.data.api.TranscribeItem
import com.example.data.api.TranscriptionClient
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
            client = fakeHttpClient
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
}
