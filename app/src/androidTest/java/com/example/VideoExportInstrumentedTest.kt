package com.example

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.SegmentEntity
import com.example.media.AudioClipDecoder
import com.example.media.BlurRegion
import com.example.media.DubTimeline
import com.example.media.DubTrackRenderer
import com.example.media.PcmAudio
import com.example.media.VideoExporter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Runs the real export pipeline (MediaCodec decoding, Sonic, Media3 Transformer, the blur shader)
 * on a device or emulator, using the small media files in src/androidTest/assets:
 * - test_video.mp4: 4 s checkerboard video with a loud 440 Hz original soundtrack,
 * - test_video_rotated.mp4: the same stream with 90 degree rotation metadata (portrait),
 * - test_clip.mp3 / test_clip.wav: 1.2 s and 0.8 s tones, like the TTS voice files.
 */
@RunWith(AndroidJUnit4::class)
class VideoExportInstrumentedTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val appContext = instrumentation.targetContext
  private lateinit var workDir: File

  @Before
  fun setUp() {
    workDir = File(appContext.cacheDir, "export_test").apply {
      deleteRecursively()
      mkdirs()
    }
  }

  @Test
  fun decodesMp3AndWavVoiceFiles() {
    val mp3 = AudioClipDecoder.decodeToMono(asset("test_clip.mp3"))
    assertEquals(24_000, mp3.sampleRate)
    assertEquals(1.2, mp3.durationSec, 0.15)
    assertTrue(rms(mp3, 0.2, 1.0) > 0.25)

    val wav = AudioClipDecoder.decodeToMono(asset("test_clip.wav"))
    assertEquals(24_000, wav.sampleRate)
    assertEquals(0.8, wav.durationSec, 0.01)
  }

  @Test
  fun exportReplacesTheOriginalAudioWithTheAiVoice() = runBlocking {
    val output = exportTestVideo("test_video.mp4", blur = null)

    assertEquals(listOf("audio/mp4a-latm", "video/avc"), trackMimeTypes(output).sorted())
    assertEquals(4.0, durationSec(output), 0.25)

    val audio = AudioClipDecoder.decodeToMono(output)
    // The original soundtrack is a loud 440 Hz tone everywhere: none of it may remain.
    assertTrue("silent before the first line: ${rms(audio, 0.05, 0.40)}", rms(audio, 0.05, 0.40) < 0.02)
    assertTrue("first AI voice: ${rms(audio, 0.65, 1.55)}", rms(audio, 0.65, 1.55) > 0.15)
    assertTrue("second AI voice: ${rms(audio, 2.1, 2.5)}", rms(audio, 2.1, 2.5) > 0.15)
    assertTrue("voice cut at the next line: ${rms(audio, 2.75, 3.9)}", rms(audio, 2.75, 3.9) < 0.02)
  }

  @Test
  fun exportBurnsTheBlurBoxIntoAPortraitVideo() = runBlocking {
    // Bottom band of the picture as displayed (the file is landscape with rotation metadata).
    val output = exportTestVideo("test_video_rotated.mp4", blur = BlurRegion(0.1f, 0.65f, 0.9f, 0.9f))

    val frame = frameAt(output, timeSec = 1.0)
    assertTrue("displayed as portrait: ${frame.width}x${frame.height}", frame.height > frame.width)
    val sharp = lumaStdDev(frame, BlurRegion(0.2f, 0.1f, 0.8f, 0.4f))
    val blurred = lumaStdDev(frame, BlurRegion(0.2f, 0.7f, 0.8f, 0.85f))
    assertTrue("checkerboard outside the box stays sharp: $sharp", sharp > 60.0)
    assertTrue("inside the box is blurred: $blurred vs $sharp", blurred < sharp * 0.4)
  }

  /** Voices at 0.5 s (MP3) and 2.0 s (WAV, cut at 2.6 s by a line without voice). */
  private suspend fun exportTestVideo(videoAsset: String, blur: BlurRegion?): File {
    val segments = listOf(
      SegmentEntity(id = 1, projectId = 1, idx = 1, start = 0.5, end = 1.9, text = "one",
        audioPath = asset("test_clip.mp3").absolutePath, audioDuration = 1.2),
      SegmentEntity(id = 2, projectId = 1, idx = 2, start = 2.0, end = 2.6, text = "two",
        audioPath = asset("test_clip.wav").absolutePath, audioDuration = 0.8),
      SegmentEntity(id = 3, projectId = 1, idx = 3, start = 2.6, end = 3.0, text = "three")
    )
    val clips = DubTimeline.plan(segments, fitMaxRate = 1.35f, timelineEndSec = 4.0)
    assertEquals(2, clips.size)

    val track = DubTrackRenderer.render(clips, durationSec = 4.0, output = File(workDir, "track.wav"))
    assertEquals(2, track.clipsWritten)

    val output = File(workDir, "export_${videoAsset}")
    withTimeout(180_000) {
      VideoExporter(appContext).export(
        videoFile = asset(videoAsset),
        audioFile = track.file,
        blur = blur,
        output = output,
        onProgress = { }
      )
    }
    assertTrue("output written", output.length() > 0)
    return output
  }

  private fun asset(name: String): File {
    val file = File(workDir, name)
    if (!file.exists()) {
      instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    return file
  }

  private fun trackMimeTypes(file: File): List<String> {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      return (0 until extractor.trackCount).map {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "?"
      }
    } finally {
      extractor.release()
    }
  }

  private fun durationSec(file: File): Double {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000.0
    } finally {
      retriever.release()
    }
  }

  private fun frameAt(file: File, timeSec: Double): Bitmap {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      return retriever.getFrameAtTime((timeSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
    } finally {
      retriever.release()
    }
  }

  private fun lumaStdDev(bitmap: Bitmap, area: BlurRegion): Double {
    val x0 = (area.left * bitmap.width).toInt()
    val x1 = (area.right * bitmap.width).toInt()
    val y0 = (area.top * bitmap.height).toInt()
    val y1 = (area.bottom * bitmap.height).toInt()
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in y0 until y1) {
      for (x in x0 until x1) {
        val c = bitmap.getPixel(x, y)
        val luma = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
        sum += luma
        sumSq += luma * luma
        n++
      }
    }
    val mean = sum / n
    return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
  }

  private fun rms(audio: PcmAudio, fromSec: Double, toSec: Double): Double {
    val from = (fromSec * audio.sampleRate).toInt().coerceIn(0, audio.samples.size)
    val to = (toSec * audio.sampleRate).toInt().coerceIn(0, audio.samples.size)
    if (to <= from) return 0.0
    var sum = 0.0
    for (i in from until to) sum += audio.samples[i].toDouble() * audio.samples[i]
    return sqrt(sum / (to - from)) / Short.MAX_VALUE
  }
}
