package com.example

import com.example.data.api.SeaLionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeaLionModelsTest {

  @Test
  fun `drop-down offers Gemma SEA-LION v4 27B first`() {
    assertEquals(
      listOf(
        "aisingapore/Gemma-SEA-LION-v4-27B-IT",
        "aisingapore/Qwen-SEA-LION-v4.5-27B-IT",
        "aisingapore/Llama-SEA-LION-v3-70B-IT"
      ),
      SeaLionModels.OPTIONS.map { it.id }
    )
    assertEquals("Gemma SEA-LION v4 27B", SeaLionModels.OPTIONS.first().label)
  }

  @Test
  fun `default model is Gemma SEA-LION v4 27B`() {
    assertEquals("aisingapore/Gemma-SEA-LION-v4-27B-IT", SeaLionModels.DEFAULT)
    assertEquals(SeaLionModels.GEMMA_V4_27B, SeaLionModels.DEFAULT)
  }

  @Test
  fun `resolve defaults blank and keeps known or previously saved models`() {
    assertEquals(SeaLionModels.DEFAULT, SeaLionModels.resolve(null))
    assertEquals(SeaLionModels.DEFAULT, SeaLionModels.resolve(""))
    assertEquals(SeaLionModels.DEFAULT, SeaLionModels.resolve("   "))
    assertEquals(
      "aisingapore/Qwen-SEA-LION-v4.5-27B-IT",
      SeaLionModels.resolve(" aisingapore/Qwen-SEA-LION-v4.5-27B-IT ")
    )
    // Saved by an older version when the model was a free-text field: never silently swap it.
    assertEquals("my-custom-model", SeaLionModels.resolve("my-custom-model"))
  }

  @Test
  fun `labelFor and isOffered`() {
    assertEquals("Gemma SEA-LION v4 27B", SeaLionModels.labelFor(SeaLionModels.GEMMA_V4_27B))
    assertEquals("Gemma SEA-LION v4 27B", SeaLionModels.labelFor(null))
    assertEquals("my-custom-model", SeaLionModels.labelFor("my-custom-model"))
    assertTrue(SeaLionModels.isOffered(SeaLionModels.GEMMA_V4_27B))
    assertFalse(SeaLionModels.isOffered("my-custom-model"))
  }
}
