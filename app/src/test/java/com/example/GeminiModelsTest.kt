package com.example

import com.example.data.api.GeminiModels
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiModelsTest {

  @Test
  fun `drop-down offers Gemini 3_8 Flash and Gemini 3_6 Flash`() {
    assertEquals(
      listOf("gemini-3.8-flash", "gemini-3.6-flash"),
      GeminiModels.OPTIONS.map { it.id }
    )
    assertEquals(
      listOf("Gemini 3.8 Flash", "Gemini 3.6 Flash"),
      GeminiModels.OPTIONS.map { it.label }
    )
  }

  @Test
  fun `default model is Gemini 3_8 Flash`() {
    assertEquals("gemini-3.8-flash", GeminiModels.DEFAULT)
  }

  @Test
  fun `resolve keeps supported models and falls back to default otherwise`() {
    assertEquals("gemini-3.6-flash", GeminiModels.resolve("gemini-3.6-flash"))
    assertEquals("gemini-3.8-flash", GeminiModels.resolve(" gemini-3.8-flash "))
    assertEquals(GeminiModels.DEFAULT, GeminiModels.resolve(null))
    assertEquals(GeminiModels.DEFAULT, GeminiModels.resolve(""))
    assertEquals(GeminiModels.DEFAULT, GeminiModels.resolve("gemini-1.0-unknown"))
  }

  @Test
  fun `labelFor returns display names`() {
    assertEquals("Gemini 3.6 Flash", GeminiModels.labelFor("gemini-3.6-flash"))
    assertEquals("Gemini 3.8 Flash", GeminiModels.labelFor(null))
  }

  @Test
  fun `generateContentUrl targets the selected model`() {
    assertEquals(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=KEY",
      GeminiModels.generateContentUrl("gemini-3.6-flash", "KEY")
    )
    assertEquals(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=KEY",
      GeminiModels.generateContentUrl("not-a-model", "KEY")
    )
  }
}
