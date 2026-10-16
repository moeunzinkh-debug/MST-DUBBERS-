package com.example

import com.example.data.api.SpeakableText
import com.example.data.api.TtsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SpeakableTextTest {

  @Test
  fun `lines with words can be voiced`() {
    listOf(
      "សួស្តី តើអ្នកសុខសប្បាយជាទេ?",
      "(សើច)",
      "OK ខ្ញុំយល់ហើយ។",
      "15",
      "១៥",
      "<i>ទៅ!</i>",
      "[piseth] ទៅ",
      "♪ La la la ♪"
    ).forEach { assertTrue(it, SpeakableText.hasWords(it)) }
  }

  @Test
  fun `lines with only symbols are skipped, as the TTS server does`() {
    listOf("", "   ", "♪♪♪", "♪ ♪", "...", "…", "?!", "— —", "<i>♪</i>", "[piseth] ♪", "♂️ ...", "🎵🎶")
      .forEach { assertFalse("'$it'", SpeakableText.hasWords(it)) }
  }

  @Test
  fun `errors are sorted into worth retrying and final`() {
    val rateLimited = TtsException.forHttpStatus(429, "", retryAfterSec = 5)
    assertEquals(TtsException.Kind.RATE_LIMITED, rateLimited.kind)
    assertEquals(5L, rateLimited.retryAfterSec)
    for (code in listOf(408, 500, 502, 503, 504)) {
      assertEquals("HTTP $code", TtsException.Kind.TEMPORARY, TtsException.forHttpStatus(code, "").kind)
    }
    for (code in listOf(400, 401, 403, 422)) {
      assertEquals("HTTP $code", TtsException.Kind.PERMANENT, TtsException.forHttpStatus(code, "bad").kind)
    }
    assertTrue(TtsException.from(SocketTimeoutException("timeout")).isRetryable)
    assertTrue(TtsException.from(UnknownHostException("takumi0002-tts-kh.hf.space")).isRetryable)
    assertFalse(TtsException.from(IllegalStateException("Gemini API key is required")).isRetryable)
    val original = TtsException("x", TtsException.Kind.NOTHING_TO_SPEAK)
    assertSame(original, TtsException.from(original))
  }
}
