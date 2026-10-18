package com.example

import com.example.media.DubTrackRenderer
import com.example.media.PcmAudio
import com.example.media.SlotFitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlotFitterTest {

  private fun tone(seconds: Double, sampleRate: Int = 24_000, frequency: Double = 440.0): PcmAudio {
    val n = (seconds * sampleRate).toInt()
    val samples = ShortArray(n) { i -> (sin(2 * PI * frequency * i / sampleRate) * 0.5 * Short.MAX_VALUE).toInt().toShort() }
    return PcmAudio(samples, sampleRate)
  }

  private fun rms(audio: PcmAudio, fromSec: Double, toSec: Double): Double {
    val from = (fromSec * audio.sampleRate).toInt().coerceIn(0, audio.samples.size)
    val to = (toSec * audio.sampleRate).toInt().coerceIn(0, audio.samples.size)
    if (to <= from) return 0.0
    var sum = 0.0
    for (i in from until to) sum += audio.samples[i].toDouble() * audio.samples[i].toDouble()
    return sqrt(sum / (to - from)) / Short.MAX_VALUE
  }

  @Test
  fun `a voice longer than its slot is sped up to fit`() {
    val out = SlotFitter.fitToSlot(tone(2.5), slotSec = 2.0, fitMaxRate = 1.35f)
    assertEquals(2.0, out.durationSec, 0.05)
    assertEquals(DubTrackRenderer.OUTPUT_SAMPLE_RATE, out.sampleRate)
    // 2.5s of speech squeezed into 2.0s is a 1.25x read — inside the cap, so
    // the whole voice must be heard from the slot start to the slot end.
    assertTrue("voice fills the slot", rms(out, 0.05, 1.95) > 0.1)
  }

  @Test
  fun `a voice shorter than its slot is slowed down and padded with silence`() {
    val out = SlotFitter.fitToSlot(tone(1.0), slotSec = 4.0, fitMaxRate = 1.35f)
    assertEquals(4.0, out.durationSec, 0.05)
    assertTrue("voice at the slot start", rms(out, 0.05, 1.0) > 0.1)
    assertTrue("the rest of the slot is silence", rms(out, 2.0, 3.9) < 0.001)
  }

  @Test
  fun `a voice far longer than its slot is cut at the slot end, not over-sped`() {
    val out = SlotFitter.fitToSlot(tone(10.0), slotSec = 1.0, fitMaxRate = 1.35f)
    assertEquals(1.0, out.durationSec, 0.05)
    assertTrue("voice still present", rms(out, 0.02, 0.9) > 0.1)
  }

  @Test
  fun `encoder silence around the speech is trimmed`() {
    val speech = tone(1.0)
    val padded = ShortArray(12_000) + speech.samples // 0.5s of leading silence
    val out = SlotFitter.fitToSlot(PcmAudio(padded, 24_000), slotSec = 3.0, fitMaxRate = 1.35f)
    assertEquals(3.0, out.durationSec, 0.05)
    assertTrue("voice starts at the slot start", rms(out, 0.02, 0.4) > 0.1)
    assertTrue("silence after the voice", rms(out, 1.6, 2.9) < 0.001)
  }

  @Test
  fun `a silent clip becomes a silent slot`() {
    val out = SlotFitter.fitToSlot(PcmAudio(ShortArray(24_000), 24_000), slotSec = 3.0, fitMaxRate = 1.35f)
    assertEquals(3.0, out.durationSec, 0.05)
    assertTrue("all silence", rms(out, 0.0, 3.0) < 0.001)
  }

  @Test
  fun `an empty clip becomes a silent slot`() {
    val out = SlotFitter.fitToSlot(PcmAudio(ShortArray(0), 24_000), slotSec = 1.5, fitMaxRate = 1.35f)
    assertEquals(1.5, out.durationSec, 0.05)
    assertTrue("all silence", rms(out, 0.0, 1.5) < 0.001)
  }
}
