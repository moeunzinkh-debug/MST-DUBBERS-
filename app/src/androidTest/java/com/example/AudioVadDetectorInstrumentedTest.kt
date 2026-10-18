package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.media.AudioVadDetector
import com.example.media.WavCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end check of [AudioVadDetector] through the real Android media stack
 * (MediaExtractor + MediaCodec): the unit tests only cover the pure waveform math, so a
 * broken decode/PTS anchor on device would otherwise go unnoticed — the detector would
 * silently return null and every dub would stay at the subtitle start.
 */
@RunWith(AndroidJUnit4::class)
class AudioVadDetectorInstrumentedTest {

  private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
  private lateinit var workDir: File

  @Before
  fun setUp() {
    workDir = File(appContext.cacheDir, "vad_test").apply {
      deleteRecursively()
      mkdirs()
    }
  }

  /** Writes a mono 16 kHz WAV from (durationSec to sine amplitude) parts. */
  private fun writeWav(name: String, vararg parts: Pair<Double, Int>, sampleRate: Int = 16_000): File {
    val totalSamples = parts.sumOf { (it.first * sampleRate).toInt() }
    val samples = ShortArray(totalSamples)
    var index = 0
    for ((durationSec, amplitude) in parts) {
      repeat((durationSec * sampleRate).toInt()) {
        if (index < samples.size) {
          samples[index] = (amplitude * sin(2.0 * PI * 440.0 * index / sampleRate)).toInt().toShort()
          index++
        }
      }
    }
    val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    data.asShortBuffer().put(samples)
    val file = File(workDir, name)
    FileOutputStream(file).use { out ->
      out.write(WavCodec.header(sampleRate, 1, samples.size * 2L))
      out.write(data.array())
    }
    return file
  }

  @Test
  fun findsOnsetAfterSilenceThroughRealDecode() {
    val file = writeWav("onset.wav", 1.0 to 100, 2.0 to 9000)
    val onset = AudioVadDetector.detectSpeechOnset(file, 0.0, 3.0)
    assertNotNull("VAD must find speech in a decoded WAV slice", onset)
    assertEquals(1.0, onset!!, 0.12)
  }

  @Test
  fun findsSpeechThatStartsAfterTheSubtitleSlotEnd() {
    // Subtitles running ahead of the picture: slot would end at 0.7s, actor starts at 1.0s.
    // The caller passes an extended search end; the onset must still be found.
    val file = writeWav("late_onset.wav", 1.0 to 100, 2.0 to 9000)
    val onset = AudioVadDetector.detectSpeechOnset(file, 0.0, 1.8)
    assertNotNull(onset)
    assertEquals(1.0, onset!!, 0.12)
  }

  @Test
  fun flatToneHasNoOnset() {
    val file = writeWav("flat.wav", 3.0 to 6000)
    assertNull(AudioVadDetector.detectSpeechOnset(file, 0.0, 3.0))
  }
}
