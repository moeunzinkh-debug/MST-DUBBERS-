package com.example

import com.example.data.db.SegmentEntity
import com.example.media.DubTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubTimelineTest {

  private fun segment(
    id: Long,
    start: Double,
    end: Double,
    audio: String? = "voice_$id.mp3",
    audioDuration: Double? = null,
    customSpeed: Float? = null
  ) = SegmentEntity(
    id = id,
    projectId = 1,
    idx = id.toInt(),
    start = start,
    end = end,
    text = "line $id",
    audioPath = audio,
    audioDuration = audioDuration,
    customSpeed = customSpeed
  )

  @Test
  fun `a manual speed wins over auto fit`() {
    val rate = DubTimeline.playbackRate(segment(1, 0.0, 2.0, audioDuration = 5.0, customSpeed = 1.5f), 1.35f)
    assertEquals(1.5f, rate, 1e-4f)
  }

  @Test
  fun `auto fit speeds up long voices up to the cap and never slows down`() {
    assertEquals(1.25f, DubTimeline.playbackRate(segment(1, 0.0, 4.0, audioDuration = 5.0), 1.35f), 1e-4f)
    assertEquals(1.35f, DubTimeline.playbackRate(segment(1, 0.0, 2.0, audioDuration = 5.0), 1.35f), 1e-4f)
    assertEquals(1.0f, DubTimeline.playbackRate(segment(1, 0.0, 4.0, audioDuration = 2.0), 1.35f), 1e-4f)
    assertEquals(1.0f, DubTimeline.playbackRate(segment(1, 0.0, 2.0, audioDuration = 5.0), 1.0f), 1e-4f)
  }

  @Test
  fun `voices are cut where the next line starts and at the end of the video`() {
    val clips = DubTimeline.plan(
      listOf(segment(2, 3.0, 4.0), segment(1, 0.5, 2.0), segment(3, 9.0, 12.0)),
      fitMaxRate = 1.35f,
      timelineEndSec = 10.0
    )
    assertEquals(listOf(1L, 2L, 3L), clips.map { it.segmentId })
    assertEquals(0.5, clips[0].startSec, 1e-9)
    assertEquals(2.5, clips[0].maxDurationSec, 1e-9)
    assertEquals(6.0, clips[1].maxDurationSec, 1e-9)
    assertEquals(1.0, clips[2].maxDurationSec, 1e-9)
  }

  @Test
  fun `a line without voice still cuts the previous voice`() {
    val clips = DubTimeline.plan(
      listOf(segment(1, 0.0, 1.0), segment(2, 1.5, 3.0, audio = null)),
      fitMaxRate = 1.35f,
      timelineEndSec = 0.0
    )
    assertEquals(1, clips.size)
    assertEquals(1.5, clips[0].maxDurationSec, 1e-9)
  }

  @Test
  fun `lines starting together keep one voice and lines after the video end are dropped`() {
    val clips = DubTimeline.plan(
      listOf(segment(1, 1.0, 2.0), segment(2, 1.0, 2.5), segment(3, 20.0, 21.0)),
      fitMaxRate = 1.35f,
      timelineEndSec = 10.0
    )
    assertEquals(listOf(1L), clips.map { it.segmentId })
    assertEquals(9.0, clips[0].maxDurationSec, 1e-9)
  }

  @Test
  fun `planned clips never overlap`() {
    val segments = (1L..40L).map { id ->
      val start = id * 1.7 % 30.0
      segment(id, start, start + 2.5, audio = if (id % 5 == 0L) null else "v$id.mp3")
    }
    val clips = DubTimeline.plan(segments, 1.35f, 31.0)
    for (i in 1 until clips.size) {
      val previous = clips[i - 1]
      assertTrue(previous.startSec + previous.maxDurationSec <= clips[i].startSec + 1e-9)
    }
  }

  @Test
  fun `calculateAutoSpeed preserves natural speed when duration is sufficient`() {
    // 28 chars in Khmer (~2.15s natural speech) easily fits into 4.6s -> should be 1.0x natural speed!
    val speed = com.example.data.SettingsRepository.calculateAutoSpeed("ខ្ញុំនឹងមកជួបអ្នកវិញក្រោយមក", 4.6)
    assertEquals(1.0f, speed, 1e-4f)
  }

  @Test
  fun `calculateAutoSpeed speeds up only when text exceeds the duration slot`() {
    // 33 chars in Khmer (~2.54s natural speech) in 1.4s slot -> needs ~1.8x
    val speed = com.example.data.SettingsRepository.calculateAutoSpeed("មនុស្សដែលស្លាប់ដោយសារពួកស៊ីកូរ", 1.4)
    assertTrue("Speed should be sped up above 1.0x when duration is tight", speed > 1.3f)
  }
}
