package com.example

import com.example.media.SpeechBlockCollector
import com.example.media.SpeechInterval
import com.example.media.WhisperSegmentCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the TurboScribe-style VAD slicing that prepares Groq Whisper uploads.
 */
class WhisperVadTest {

    private val rate = 16_000

    private fun silence(seconds: Int) = ShortArray(seconds * rate)

    private fun tone(seconds: Double, amplitude: Short = 3000): ShortArray {
        val count = (seconds * rate).toInt()
        return ShortArray(count) { amplitude }
    }

    @Test
    fun `long silences split blocks and pads are trimmed`() {
        // Mirrors the donghua example: 4s intro silence, a line, a 14s silent fight
        // scene, another line. Whisper must never see the silences.
        val pcm = silence(4) + tone(2.0) + silence(14) + tone(1.0)

        val blocks = SpeechBlockCollector(rate).apply { append(pcm) }.finish()

        assertEquals(2, blocks.size)

        val first = blocks[0]
        assertEquals(3.75, first.startSec, 0.01) // 4.0 minus the 0.25s pad
        assertEquals(2.5 * rate, first.samples.size.toDouble(), 400.0) // 2.25s speech + pad
        assertEquals(listOf(SpeechInterval(0.25, 2.25)), first.regions)

        val second = blocks[1]
        assertEquals(19.75, second.startSec, 0.01) // 20.0 minus the pad
        assertEquals(listOf(SpeechInterval(0.25, 1.25)), second.regions)
    }

    @Test
    fun `short pauses keep one block but split speech regions`() {
        val pcm = tone(1.0) + silence(1) + tone(1.0)

        val blocks = SpeechBlockCollector(rate).apply { append(pcm) }.finish()

        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].regions.size)
        // Speech starts at t=0, so no leading pad fits in the first region.
        assertEquals(SpeechInterval(0.0, 1.0), blocks[0].regions[0])
        assertEquals(SpeechInterval(2.0, 3.0), blocks[0].regions[1])
    }

    @Test
    fun `blocks longer than the cap are hard-cut with continuing regions`() {
        val pcm = tone(10.0)
        val collector = SpeechBlockCollector(rate, maxBlockSec = 5.0)
        val blocks = collector.apply { append(pcm) }.finish()

        assertEquals(2, blocks.size)
        assertEquals(0.0, blocks[0].startSec, 0.01)
        assertEquals(5.0, blocks[0].regions.single().endSec, 0.01)
        assertEquals(5.0, blocks[1].startSec, 0.01)
        assertEquals(0.0, blocks[1].regions.single().startSec, 0.01)
    }

    @Test
    fun `pure silence produces no blocks`() {
        val blocks = SpeechBlockCollector(rate).apply { append(silence(10)) }.finish()
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `a tiny blip below the region minimum is discarded`() {
        val pcm = silence(2) + tone(0.1) + silence(2)
        val blocks = SpeechBlockCollector(rate).apply { append(pcm) }.finish()
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `streaming appends produce the same blocks as one shot`() {
        val pcm = silence(1) + tone(1.5) + silence(5) + tone(0.8)
        val oneShot = SpeechBlockCollector(rate).apply { append(pcm) }.finish()

        val streamed = SpeechBlockCollector(rate).apply {
            var i = 0
            while (i < pcm.size) {
                val end = minOf(i + 7777, pcm.size)
                append(pcm.copyOfRange(i, end), end - i)
                i = end
            }
        }.finish()

        assertEquals(oneShot.size, streamed.size)
        oneShot.zip(streamed).forEach { (a, b) ->
            assertEquals(a.startSec, b.startSec, 0.001)
            assertEquals(a.regions, b.regions)
            assertEquals(a.samples.size, b.samples.size)
        }
    }
}

class WhisperSegmentCleanerTest {

    @Test
    fun `stretched segments are clamped onto speech regions`() {
        val regions = listOf(SpeechInterval(0.25, 2.25))
        val clamped = WhisperSegmentCleaner.clampToSpeechRegions(
            WhisperSegmentCleaner.TimedText(0.0, 4.94, "line"),
            regions
        )
        assertEquals(WhisperSegmentCleaner.TimedText(0.25, 2.25, "line"), clamped)
    }

    @Test
    fun `segments inside silence are dropped as hallucinations`() {
        val regions = listOf(SpeechInterval(0.25, 2.25))
        assertNull(
            WhisperSegmentCleaner.clampToSpeechRegions(
                WhisperSegmentCleaner.TimedText(3.0, 4.0, "phantom"),
                regions
            )
        )
    }

    @Test
    fun `segments spanning several regions only snap at the edges`() {
        val regions = listOf(SpeechInterval(0.25, 2.25), SpeechInterval(3.0, 4.4))
        val clamped = WhisperSegmentCleaner.clampToSpeechRegions(
            WhisperSegmentCleaner.TimedText(0.1, 4.9, "two lines"),
            regions
        )
        assertEquals(WhisperSegmentCleaner.TimedText(0.25, 4.4, "two lines"), clamped)
    }

    @Test
    fun `credit-roll hallucinations are recognised`() {
        assertTrue(WhisperSegmentCleaner.isLikelyHallucination("♪"))
        assertTrue(WhisperSegmentCleaner.isLikelyHallucination("   "))
        assertTrue(WhisperSegmentCleaner.isLikelyHallucination("Subtitles by Amara.org"))
        assertTrue(WhisperSegmentCleaner.isLikelyHallucination("字幕由XX提供"))
        assertTrue(!WhisperSegmentCleaner.isLikelyHallucination("សួស្តី ម៉េចហើយ"))
        assertTrue(!WhisperSegmentCleaner.isLikelyHallucination("相比于人类"))
    }

    @Test
    fun `tiny fragments are merged into their neighbour`() {
        val merged = WhisperSegmentCleaner.coalesceFragments(
            listOf(
                WhisperSegmentCleaner.TimedText(1.0, 1.25, "តូច"),
                WhisperSegmentCleaner.TimedText(1.4, 3.2, "ធំជាង")
            )
        )
        assertEquals(1, merged.size)
        assertEquals("តូច ធំជាង", merged.single().text)
        assertEquals(1.0, merged.single().startSec, 0.001)
        assertEquals(3.2, merged.single().endSec, 0.001)
    }

    @Test
    fun `full-length segments are left alone`() {
        val input = listOf(
            WhisperSegmentCleaner.TimedText(1.0, 3.0, "one"),
            WhisperSegmentCleaner.TimedText(3.5, 5.0, "two")
        )
        assertEquals(input, WhisperSegmentCleaner.coalesceFragments(input))
    }
}
