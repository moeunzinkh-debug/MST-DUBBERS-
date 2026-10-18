package com.example

import com.example.media.SpeechOnsetFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechOnsetFinderTest {

  private val sampleRate = 16_000

  /** Builds a mono signal from (durationSec to sine amplitude) parts. */
  private fun signal(vararg parts: Pair<Double, Int>): ShortArray {
    val totalSamples = parts.sumOf { (it.first * sampleRate).toInt() }
    val out = ShortArray(totalSamples)
    var index = 0
    for ((durationSec, amplitude) in parts) {
      val count = (durationSec * sampleRate).toInt()
      for (i in 0 until count) {
        if (index >= out.size) break
        out[index] = (amplitude * sin(2.0 * PI * 440.0 * index / sampleRate)).toInt().toShort()
        index++
      }
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
    // Steady music/noise filling the whole slot: the old detector reported the slot start.
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
  fun `a single click is not mistaken for speech`() {
    val samples = signal(0.5 to 100, 0.025 to 12000, 0.975 to 100, 1.5 to 9000)
    assertEquals(1.5, SpeechOnsetFinder.findOnsetSec(samples, sampleRate, 0.0)!!, 0.03)
  }

  @Test
  fun `too little audio has no onset`() {
    assertNull(SpeechOnsetFinder.findOnsetSec(signal(0.05 to 9000), sampleRate, 0.0))
  }
}
