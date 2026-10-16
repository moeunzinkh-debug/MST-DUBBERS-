package com.example

import com.example.data.api.EdgeTtsClient
import com.example.data.api.TtsException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Talks to a local fake of the Edge TTS server (same answers as the real FastAPI server). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EdgeTtsClientTest {

  @get:Rule val tmp = TemporaryFolder()

  private class Answer(val code: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())

  private lateinit var server: HttpServer
  private var serverRunning = false
  private val requests = CopyOnWriteArrayList<String>()

  @Volatile
  private var answer: (method: String, path: String) -> Answer = { _, _ -> Answer(404, "Not Found".toByteArray()) }

  private val fakeMp3 = ByteArray(4000) { i -> if (i < 3) "ID3"[i].code.toByte() else (i % 251).toByte() }

  @Before
  fun startServer() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
      exchange.requestBody.readBytes()
      val reply = answer(exchange.requestMethod, exchange.requestURI.path)
      reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
      exchange.sendResponseHeaders(reply.code, if (reply.body.isEmpty()) -1 else reply.body.size.toLong())
      if (reply.body.isNotEmpty()) exchange.responseBody.use { it.write(reply.body) }
      exchange.close()
    }
    server.start()
    serverRunning = true
  }

  @After
  fun stopServer() {
    if (serverRunning) server.stop(0)
  }

  private fun client() = EdgeTtsClient { "http://127.0.0.1:${server.address.port}" }

  private fun speak(text: String = "សួស្តី") = runBlocking {
    client().generateSpeech(text, "km-KH-SreymomNeural", 1.2f, tmp.newFile())
  }

  private fun failure(text: String = "សួស្តី"): TtsException {
    try {
      speak(text)
    } catch (e: TtsException) {
      return e
    }
    fail("expected a TtsException")
    throw AssertionError()
  }

  @Test
  fun `audio from the Edge TTS server is saved`() {
    answer = { method, path ->
      if (method == "POST" && path == "/api/process-multi") Answer(200, fakeMp3, mapOf("Content-Type" to "audio/mpeg"))
      else Answer(404)
    }
    val file = tmp.newFile()
    val duration = runBlocking { client().generateSpeech("សួស្តី", "km-KH-SreymomNeural", 1.2f, file) }
    assertArrayEquals(fakeMp3, file.readBytes())
    assertTrue(duration >= 0.5)
    assertEquals(listOf("POST /api/process-multi"), requests.toList())
  }

  @Test
  fun `a line with only symbols is not sent to the server`() {
    val error = failure("♪♪♪")
    assertEquals(TtsException.Kind.NOTHING_TO_SPEAK, error.kind)
    assertTrue(requests.isEmpty())
  }

  @Test
  fun `no audio for a line with words is temporary, and other endpoints are not tried`() {
    // What the real server answers when Edge TTS failed 3 times for the line.
    answer = { _, _ -> Answer(400, """{"detail":"No audio generated"}""".toByteArray(), mapOf("Content-Type" to "application/json")) }
    val error = failure()
    assertEquals(TtsException.Kind.TEMPORARY, error.kind)
    assertEquals(listOf("POST /api/process-multi"), requests.toList())
  }

  @Test
  fun `too many requests asks to wait`() {
    answer = { _, _ -> Answer(429, "Too Many Requests".toByteArray(), mapOf("Retry-After" to "7")) }
    val error = failure()
    assertEquals(TtsException.Kind.RATE_LIMITED, error.kind)
    assertEquals(7L, error.retryAfterSec)
  }

  @Test
  fun `server errors are temporary and keep the server's reason`() {
    answer = { _, _ -> Answer(502, """{"detail":"Speech synthesis failed: boom"}""".toByteArray()) }
    val error = failure()
    assertEquals(TtsException.Kind.TEMPORARY, error.kind)
    assertTrue(error.message, error.message!!.contains("Speech synthesis failed: boom"))
  }

  @Test
  fun `other servers can still answer on the generic endpoints`() {
    answer = { method, path ->
      if (method == "GET" && path == "/tts") Answer(200, fakeMp3, mapOf("Content-Type" to "audio/mpeg"))
      else Answer(404, "Not Found".toByteArray())
    }
    val file = tmp.newFile()
    runBlocking { client().generateSpeech("hello", "km-KH-PisethNeural", 1f, file) }
    assertArrayEquals(fakeMp3, file.readBytes())
    assertEquals(listOf("POST /api/process-multi", "GET /tts"), requests.toList())
  }

  @Test
  fun `a server without any speech endpoint is a permanent failure`() {
    val error = failure()
    assertEquals(TtsException.Kind.PERMANENT, error.kind)
  }

  @Test
  fun `an unreachable server is a temporary failure`() {
    server.stop(0)
    serverRunning = false
    val error = failure()
    assertEquals(TtsException.Kind.TEMPORARY, error.kind)
  }
}
