package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SettingsRepository
import com.example.media.BlurRegion
import com.example.media.BlurSettings
import com.example.media.BlurStrength
import com.example.media.DubClip
import com.example.media.DubTrackRenderer
import com.example.media.PcmAudio
import com.example.media.WavCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DubTrackRendererTest {

  @get:Rule val tmp = TemporaryFolder()

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
    for (i in from until to) sum += audio.samples[i].toDouble() * audio.samples[i]
    return sqrt(sum / (to - from)) / Short.MAX_VALUE
  }

  @Test
  fun `voices are placed at their start time and cut where the next line starts`() {
    val first = tmp.newFile("first.mp3")
    val second = tmp.newFile("second.mp3")
    val decoded = mapOf(first.name to tone(1.0), second.name to tone(3.0))
    val clips = listOf(
      // Ends on its own at 1.5 s.
      DubClip(1, first.absolutePath, startSec = 0.5, rate = 1f, maxDurationSec = 2.0),
      // 3 s of audio, but the next line starts 1 s later.
      DubClip(2, second.absolutePath, startSec = 2.5, rate = 1f, maxDurationSec = 1.0)
    )
    val output = File(tmp.root, "track.wav")

    val result = DubTrackRenderer.render(clips, durationSec = 5.0, output = output, decode = { decoded.getValue(it.name) })

    assertEquals(2, result.clipsWritten)
    assertEquals(0, result.clipsFailed)
    val track = WavCodec.readPcm16(output)
    assertNotNull(track)
    track!!
    assertEquals(DubTrackRenderer.OUTPUT_SAMPLE_RATE, track.sampleRate)
    assertEquals(5.0, track.durationSec, 0.001)
    assertTrue("silent before the first line", rms(track, 0.0, 0.45) < 0.001)
    assertTrue("first voice", rms(track, 0.6, 1.4) > 0.2)
    assertTrue("silent after the first voice ends", rms(track, 1.56, 2.45) < 0.001)
    assertTrue("second voice", rms(track, 2.6, 3.45) > 0.2)
    assertTrue("second voice is cut", rms(track, 3.55, 5.0) < 0.001)
  }

  @Test
  fun `speed and sample rate changes give the expected duration`() {
    val input = tone(2.0)
    val faster = DubTrackRenderer.timeStretch(input, speed = 1.5f, outputSampleRate = 24_000)
    assertEquals(2.0 / 1.5, faster.size / 24_000.0, 0.05)

    val resampled = DubTrackRenderer.timeStretch(input, speed = 1f, outputSampleRate = 44_100)
    assertEquals(2.0, resampled.size / 44_100.0, 0.05)
  }

  @Test
  fun `unreadable voice files are skipped and the track keeps its length`() {
    val output = File(tmp.root, "track.wav")
    val result = DubTrackRenderer.render(
      listOf(DubClip(1, "/missing.mp3", startSec = 0.5, rate = 1f, maxDurationSec = 1.0)),
      durationSec = 2.0,
      output = output,
      decode = { throw IOException("broken file") }
    )
    assertEquals(0, result.clipsWritten)
    assertEquals(1, result.clipsFailed)
    assertEquals(2.0, WavCodec.readPcm16(output)!!.durationSec, 0.001)
  }

  @Test
  fun `stereo wav files with extra chunks are read as mono`() {
    val frames = 1000
    val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frames) {
      pcm.putShort(1000)
      pcm.putShort(3000)
    }
    val list = "LIST".toByteArray() + intLe(3) + byteArrayOf(1, 2, 3, 0) // odd size + padding byte
    val fmt = "fmt ".toByteArray() + intLe(16) + shortLe(1) + shortLe(2) + intLe(22_050) +
      intLe(22_050 * 4) + shortLe(4) + shortLe(16)
    val data = "data".toByteArray() + intLe(frames * 4) + pcm.array()
    val body = "WAVE".toByteArray() + fmt + list + data
    val file = tmp.newFile("stereo.wav")
    file.writeBytes("RIFF".toByteArray() + intLe(body.size) + body)

    val audio = WavCodec.readPcm16(file)!!
    assertEquals(22_050, audio.sampleRate)
    assertEquals(frames, audio.samples.size)
    assertEquals(2000, audio.samples[0].toInt())
  }

  @Test
  fun `the blur box is remembered per project`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = SettingsRepository(context)
    assertEquals(BlurSettings(), repository.loadBlurSettings(7))

    val custom = BlurSettings(enabled = true, region = BlurRegion(0.1f, 0.2f, 0.5f, 0.4f), strength = BlurStrength.STRONG)
    repository.saveBlurSettings(7, custom)
    assertEquals(custom, SettingsRepository(context).loadBlurSettings(7))
    assertEquals(BlurSettings(), repository.loadBlurSettings(8))

    // Out-of-range strengths are stored clamped.
    repository.saveBlurSettings(9, BlurSettings(strength = 7f))
    assertEquals(1f, repository.loadBlurSettings(9).strength, 0f)

    repository.clearBlurSettings(7)
    assertFalse(repository.loadBlurSettings(7).enabled)
    assertEquals(BlurStrength.DEFAULT, repository.loadBlurSettings(7).strength, 0f)
  }

  private fun intLe(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

  private fun shortLe(value: Int): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
