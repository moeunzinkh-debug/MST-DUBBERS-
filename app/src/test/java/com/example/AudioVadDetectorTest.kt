package com.example

import com.example.media.AudioVadDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioVadDetectorTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `non-existent file returns null`() {
    val result = AudioVadDetector.detectSpeechOnset(File("non_existent.mp4"), 0.0, 5.0)
    assertNull(result)
  }

  @Test
  fun `invalid time range returns null`() {
    val file = tmp.newFile()
    assertNull(AudioVadDetector.detectSpeechOnset(file, 5.0, 2.0))
  }
}
