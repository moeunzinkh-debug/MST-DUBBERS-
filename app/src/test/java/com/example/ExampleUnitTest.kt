package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun testSubtitleParsingAndFormatting() {
    val srtContent = """
        1
        00:00:01,500 --> 00:00:04,200
        Welcome to DAI Dubber studio.

        2
        00:00:05,000 --> 00:00:08,100
        AI speech synthesis in action.
    """.trimIndent()

    val cues = com.example.util.SubtitleParser.parseSubtitles(srtContent)
    assertEquals(2, cues.size)
    assertEquals(1.5, cues[0].start, 0.01)
    assertEquals(4.2, cues[0].end, 0.01)
    assertEquals("Welcome to DAI Dubber studio.", cues[0].text)

    val exportedSrt = com.example.util.SubtitleParser.toSrt(cues)
    assertTrue(exportedSrt.contains("00:00:01,500 --> 00:00:04,200"))
    assertTrue(exportedSrt.contains("Welcome to DAI Dubber studio."))

    val exportedVtt = com.example.util.SubtitleParser.toVtt(cues)
    assertTrue(exportedVtt.startsWith("WEBVTT"))
    assertTrue(exportedVtt.contains("00:00:01.500 --> 00:00:04.200"))
  }

  @Test
  fun testWavHelperGeneratesValidHeader() {
    val dummyPcm = ByteArray(4800) // 100ms of 24kHz 16-bit mono
    val wav = com.example.util.WavHelper.pcmToWav(dummyPcm, sampleRate = 24000)
    assertEquals(44 + dummyPcm.size, wav.size)
    assertEquals('R'.code.toByte(), wav[0])
    assertEquals('I'.code.toByte(), wav[1])
    assertEquals('F'.code.toByte(), wav[2])
    assertEquals('F'.code.toByte(), wav[3])
    assertEquals('W'.code.toByte(), wav[8])
    assertEquals('A'.code.toByte(), wav[9])
    assertEquals('V'.code.toByte(), wav[10])
    assertEquals('E'.code.toByte(), wav[11])
  }
}
