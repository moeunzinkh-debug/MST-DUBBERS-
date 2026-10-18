package com.example

import com.example.media.SpeechOnsetFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechOnsetFinderTest {

  private val sampleRate = 16_000

  /** Builds a mono signal from (durationSec to sine amplitude) parts, one after another. */
  private fun signal(vararg parts: Pair<Double, Int>): ShortArray =
    layered(0.0, *parts.map { Tone(it.first, 440, it.second) }.toTypedArray())

  private data class Tone(val durationSec: Double, val freq: Int, val amplitude: Int)

  private fun layered(slicePaddingSec: Double = 0.0, vararg tones: Tone): ShortArray {
    val totalSamples = tones.sumOf { (it.durationSec * sampleRate).toInt() }
    val out = ShortArray(totalSamples)
    var index = 0
    for (tone in tones) {
      repeat((tone.durationSec * sampleRate).toInt()) {
        if (index < out.size) {
          out[index] = (tone.amplitude * sin(2.0 * PI * tone.freq * index / sampleRate)).toInt().toShort()
          index++
        }
      }
    }
    return out
  }

  /** Continuous music tone, with extra tones (the "voice") mixed in from [Layer.startSec]. */
  private data class Layer(val startSec: Double, val durationSec: Double, val freq: Int, val amplitude: Int)

  private fun musicWithLayers(
    musicFreq: Int,
    musicAmplitude: Int,
    totalSec: Double,
    vararg layers: Layer
  ): ShortArray {
    val out = ShortArray((totalSec * sampleRate).toInt())
    for (i in out.indices) {
      var v = musicAmplitude * sin(2.0 * PI * musicFreq * i / sampleRate)
      for (layer in layers) {
        val start = (layer.startSec * sampleRate).toInt()
        val end = ((layer.startSec + layer.durationSec) * sampleRate).toInt()
        if (i in start until end) {
          v += layer.amplitude * sin(2.0 * PI * layer.freq * i / sampleRate)
        }
      }
      out[i] = v.toInt().toShort()
    }
    return out
  }

  @Test
  fun `silence has no onset`() {
    assertNull(SpeechOnsetFinder.findOnsetSec(signal(2.0 to 0), sampleRate, 0.0))
    assertNull(SpeechOnsetFinder.findOnsetSec(ShortArray(0), sampleRate, 0.0))
  }

  @Test
  fun `flat loud bed has no onset instead of frame zero`() {
    // Steady music/noise filling the whole slot: must not report the slot start.
    assertNull(SpeechOnsetFinder.findOnsetSec(signal(3.0 to 6000), sampleRate, 4.0))
  }

  @Test
  fun `speech after silence is found at the waveform onset`() {
    val samples = signal(1.0 to 100, 2.0 to 9000)
    assertEquals(11.0, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 10.0)!!, 0.03)
  }

  @Test
  fun `soft beginning of a line is found by walking back from the loud part`() {
    // Quiet consonant/breathy start at 0.8s, full voice only at 1.2s.
    val samples = signal(0.8 to 100, 0.4 to 1500, 1.8 to 9000)
    assertEquals(0.8, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 0.0)!!, 0.03)
  }

  @Test
  fun `speech over a steady music bed is not reported at the slice start`() {
    val samples = signal(1.5 to 2500, 1.5 to 9000)
    assertEquals(6.5, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 5.0)!!, 0.03)
  }

  @Test
  fun `speech starting over continuous music is found`() {
    // The key failure: music plays first, the actor starts speaking over it at 1.2s.
    // Total loudness only rises ~1.4x, so an absolute threshold misses this completely.
    val samples = musicWithLayers(150, 5000, 3.0, Layer(1.2, 1.8, 1000, 5000))
    assertEquals(1.2, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 0.0)!!, 0.03)
  }

  @Test
  fun `quiet speech over loud music is found by its high frequency step`() {
    val samples = musicWithLayers(150, 6000, 3.0, Layer(1.5, 1.5, 1200, 3500))
    assertEquals(3.5, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 2.0)!!, 0.03)
  }

  @Test
  fun `a music burst before the speech is not the onset`() {
    val samples = musicWithLayers(
      200, 3000, 3.0,
      Layer(0.6, 0.1, 800, 6000), // short musical accent
      Layer(1.8, 1.2, 1000, 5000) // the actual line
    )
    assertEquals(1.8, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 0.0)!!, 0.03)
  }

  @Test
  fun `previous line tail then silence then speech finds the new line`() {
    val samples = musicWithLayers(
      440, 0, 3.0,
      Layer(0.0, 0.3, 440, 9000),
      Layer(1.2, 1.8, 440, 9000)
    )
    assertEquals(1.2, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 0.0)!!, 0.03)
  }

  @Test
  fun `a pause inside ongoing speech is not a new onset`() {
    val samples = signal(0.4 to 9000, 0.3 to 2000, 0.4 to 9000, 0.3 to 2500, 1.6 to 9000)
    assertNull(SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 7.0))
  }

  @Test
  fun `a single click is not mistaken for speech`() {
    val samples = signal(0.5 to 100, 0.025 to 12000, 0.975 to 100, 1.5 to 9000)
    assertEquals(1.5, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 0.0)!!, 0.03)
  }

  @Test
  fun `too little audio has no onset`() {
    assertNull(SpeechOnsetFinder.findOnsetSec(signal(0.05 to 9000), sampleRate, 0.0))
  }
}
