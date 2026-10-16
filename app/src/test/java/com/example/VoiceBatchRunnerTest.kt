package com.example

import com.example.data.VoiceBatchProgress
import com.example.data.VoiceBatchRunner
import com.example.data.api.EdgeTtsClient
import com.example.data.api.TtsException
import com.example.data.api.TtsException.Kind.NOTHING_TO_SPEAK
import com.example.data.api.TtsException.Kind.RATE_LIMITED
import com.example.data.api.TtsException.Kind.TEMPORARY
import com.example.data.db.SegmentEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBatchRunnerTest {

  private fun lines(n: Int) = (1..n).map {
    SegmentEntity(id = it.toLong(), projectId = 1, idx = it, start = it.toDouble(), end = it + 0.8, translation = "ខ្សែទី $it")
  }

  private val sleeps = mutableListOf<Long>()

  private fun runner(maxConsecutiveFailures: Int = 4) =
    VoiceBatchRunner(maxConsecutiveFailures = maxConsecutiveFailures, sleep = { sleeps += it })

  @Test
  fun `a line with nothing to read no longer stops the run`() = runBlocking<Unit> {
    // Regression: the run stopped at the first failing line (e.g. line 15 = "♪♪"), and starting
    // it again stopped at the same line, so only lines 1 to 14 ever got a voice.
    val voicedIds = mutableListOf<Long>()
    val outcome = runner().run(lines(30), generate = { seg, _ ->
      if (seg.idx == 15) throw TtsException(EdgeTtsClient.NO_WORDS_MESSAGE, NOTHING_TO_SPEAK)
      voicedIds += seg.id
    })
    assertEquals(29, outcome.voiced.size)
    assertEquals(setOf(15L), outcome.skipped.keys)
    assertTrue(outcome.failed.isEmpty())
    assertFalse(outcome.stoppedEarly)
    assertEquals((1L..30L).filter { it != 15L }, voicedIds)
    assertTrue("no waiting for a line that can't be read", sleeps.isEmpty())
  }

  @Test
  fun `temporary failures are retried after a short wait`() = runBlocking<Unit> {
    var calls = 0
    val outcome = runner().run(lines(1), generate = { _, _ ->
      calls++
      if (calls < 3) throw TtsException("busy", TEMPORARY)
    })
    assertEquals(listOf(1L), outcome.voiced)
    assertEquals(3, calls)
    assertEquals(listOf(2_000L, 6_000L), sleeps)
  }

  @Test
  fun `the server's Retry-After is honoured, up to a limit`() = runBlocking<Unit> {
    var calls = 0
    val outcome = runner().run(lines(2), generate = { seg, _ ->
      calls++
      if (seg.idx == 1 && calls == 1) throw TtsException("slow down", RATE_LIMITED, retryAfterSec = 7)
      if (seg.idx == 2 && calls == 3) throw TtsException("slow down", RATE_LIMITED, retryAfterSec = 600)
    })
    assertEquals(listOf(1L, 2L), outcome.voiced)
    assertEquals(listOf(7_000L, 30_000L), sleeps)
  }

  @Test
  fun `a line that keeps failing is recorded and the run goes on`() = runBlocking<Unit> {
    val outcome = runner().run(lines(5), generate = { seg, _ ->
      if (seg.idx == 2) throw TtsException("Voice server error (HTTP 502)", TEMPORARY)
    })
    assertEquals(listOf(1L, 3L, 4L, 5L), outcome.voiced)
    assertEquals(mapOf(2L to "Voice server error (HTTP 502)"), outcome.failed)
    assertEquals("3 attempts for line 2", listOf(2_000L, 6_000L), sleeps)
  }

  @Test
  fun `permanent errors are not retried`() = runBlocking<Unit> {
    var calls = 0
    val outcome = runner().run(lines(1), generate = { _, _ ->
      calls++
      throw IllegalStateException("No TTS server address")
    })
    assertEquals(1, calls)
    assertEquals(mapOf(1L to "No TTS server address"), outcome.failed)
    assertTrue(sleeps.isEmpty())
  }

  @Test
  fun `the run stops when many lines in a row fail`() = runBlocking<Unit> {
    val tried = mutableSetOf<Int>()
    val outcome = runner(maxConsecutiveFailures = 4).run(lines(20), generate = { seg, _ ->
      tried += seg.idx
      if (seg.idx >= 3) throw TtsException("No connection to the voice server", TEMPORARY)
    })
    assertTrue(outcome.stoppedEarly)
    assertEquals(listOf(1L, 2L), outcome.voiced)
    assertEquals(setOf(3L, 4L, 5L, 6L), outcome.failed.keys)
    assertEquals((1..6).toSet(), tried)
  }

  @Test
  fun `lines with nothing to read don't count as failures in a row`() = runBlocking<Unit> {
    val outcome = runner(maxConsecutiveFailures = 2).run(lines(6), generate = { seg, _ ->
      if (seg.idx in 2..5) throw TtsException(EdgeTtsClient.NO_WORDS_MESSAGE, NOTHING_TO_SPEAK)
    })
    assertFalse(outcome.stoppedEarly)
    assertEquals(listOf(1L, 6L), outcome.voiced)
    assertEquals(4, outcome.skipped.size)
  }

  @Test
  fun `only the last attempt may use the backup voice engine`() = runBlocking<Unit> {
    val lastAttemptFlags = mutableListOf<Boolean>()
    runner().run(lines(1), generate = { _, lastAttempt ->
      lastAttemptFlags += lastAttempt
      if (!lastAttempt) throw TtsException("busy", TEMPORARY)
    })
    assertEquals(listOf(false, false, true), lastAttemptFlags)
  }

  @Test
  fun `progress counts the lines and announces waits`() = runBlocking<Unit> {
    val updates = mutableListOf<VoiceBatchProgress>()
    var failedOnce = false
    val lineResults = mutableListOf<Pair<Long, TtsException?>>()
    runner().run(
      lines(3),
      generate = { seg, _ ->
        if (seg.idx == 2 && !failedOnce) {
          failedOnce = true
          throw TtsException("busy", TEMPORARY)
        }
      },
      onLineDone = { seg, error -> lineResults += seg.id to error },
      onProgress = { updates += it }
    )
    assertEquals(VoiceBatchProgress(0, 3), updates.first())
    assertTrue(updates.any { it.done == 1 && it.retryInSec == 2 })
    assertEquals(VoiceBatchProgress(3, 3, voiced = 3), updates.last())
    assertEquals(listOf(1L to null, 2L to null, 3L to null), lineResults)
  }

  @Test
  fun `stopping the run cancels it right away`() = runBlocking<Unit> {
    val generated = mutableListOf<Int>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      runner().run(lines(10), generate = { seg, _ ->
        generated += seg.idx
        if (seg.idx == 3) awaitCancellation()
      })
    }
    job.cancelAndJoin()
    assertTrue(job.isCancelled)
    assertEquals(listOf(1, 2, 3), generated)
  }
}
