package com.example.media

import kotlin.math.sqrt

/** A time interval on the media timeline, in seconds. */
data class SpeechInterval(val startSec: Double, val endSec: Double)

/**
 * Slices a decoded mono PCM stream into speech blocks cut at silent scenes — the same trick
 * that makes TurboScribe's timestamps land on the actual dialogue.
 *
 * Raw Whisper runs stretch segments across silence: a line spoken at 00:21 comes back as
 * 00:07–00:23 because Whisper "fills" the 14-second fight-scene pause before it, and the
 * first line of a video starts at 00:00 even when the intro music runs for four seconds.
 * TurboScribe avoids this by running VAD (voice activity detection) over the audio and only
 * showing Whisper the speech; this collector reproduces that behaviour on-device:
 *
 *  - every 25 ms frame is classified speech/quiet by RMS energy;
 *  - a pause of [splitSilenceSec] or longer closes the current block, so silent scenes never
 *    reach Whisper and can never absorb a timestamp;
 *  - each block is trimmed to [speechPadSec] around its first/last speech frame;
 *  - blocks are additionally hard-cut at [maxBlockSec] so uploads stay small;
 *  - the per-block speech regions are reported back so returned Whisper segments can be
 *    clamped to real speech (see [WhisperSegmentCleaner]).
 */
class SpeechBlockCollector(
    private val sampleRate: Int,
    private val maxBlockSec: Double = DEFAULT_MAX_BLOCK_SEC,
    private val splitSilenceSec: Double = DEFAULT_SPLIT_SILENCE_SEC,
    private val regionMergeGapSec: Double = DEFAULT_REGION_MERGE_GAP_SEC,
    private val speechPadSec: Double = DEFAULT_SPEECH_PAD_SEC,
    private val minRegionSec: Double = DEFAULT_MIN_REGION_SEC,
    private val frameMs: Long = DEFAULT_FRAME_MS,
    private val audibleRms: Double = DEFAULT_AUDIBLE_RMS
) {

    /** One VAD-sliced audio block. [startSec] is absolute on the media timeline; [regions] are relative to the block. */
    data class Block(
        val samples: ShortArray,
        val sampleRate: Int,
        val startSec: Double,
        val regions: List<SpeechInterval>
    )

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(maxBlockSec > 1.0) { "maxBlockSec must exceed 1 second" }
        require(splitSilenceSec > regionMergeGapSec) { "splitSilenceSec must exceed regionMergeGapSec" }
        require(speechPadSec >= 0.0) { "speechPadSec cannot be negative" }
    }

    private val frameSamples: Int = ((frameMs * sampleRate) / 1000).toInt().coerceAtLeast(1)
    private val frameSec: Double = frameSamples.toDouble() / sampleRate
    private val maxBlockSamples: Long = (maxBlockSec * sampleRate).toLong()
    private val splitSilenceFrames: Int =
        kotlin.math.ceil(splitSilenceSec / frameSec).toInt().coerceAtLeast(1)
    private val regionMergeGapFrames: Int =
        kotlin.math.ceil(regionMergeGapSec / frameSec).toInt().coerceAtLeast(1)
    private val padSamples: Int = (speechPadSec * sampleRate).toInt()

    private val emitted = mutableListOf<Block>()

    /** PCM of the block currently being collected (empty when no block is open). */
    private val builder = ShortArrayBuilder(frameSamples * 8)

    /** Rolling history so a block can begin [speechPadSec] before its first speech frame. */
    private val ring = ShortArray(padSamples + 4 * frameSamples + 16)
    private var ringWrite = 0
    private var ringCount = 0

    private val frameAccum = ShortArray(frameSamples)
    private var framePos = 0
    private var frameSumSq = 0.0

    private var totalSamples = 0L
    private var framesFed = 0L

    private var blockStartSample = -1L
    private val regions = mutableListOf<SpeechInterval>()

    private var inSpeech = false
    private var regionStartFrame = -1L
    private var lastSpeechFrame = -1L
    private var silenceRun = 0

    /** Feeds decoded mono PCM into the collector. */
    fun append(samples: ShortArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val s = samples[i]
            frameAccum[framePos] = s
            frameSumSq += s.toDouble() * s
            framePos++
            totalSamples++
            if (framePos == frameSamples) {
                val rms = sqrt(frameSumSq / frameSamples)
                frameSumSq = 0.0
                framePos = 0
                completeFrame(frameSamples, rms)
            }
            i++
        }
    }

    /** Returns and forgets the blocks completed so far (for streaming callers). */
    fun takeBlocks(): List<Block> {
        if (emitted.isEmpty()) return emptyList()
        val blocks = emitted.toList()
        emitted.clear()
        return blocks
    }

    /** Closes any open block and returns everything collected so far. */
    fun finish(): List<Block> {
        if (framePos > 0) {
            val partialCount = framePos
            val rms = sqrt(frameSumSq / partialCount)
            frameSumSq = 0.0
            framePos = 0
            completeFrame(partialCount, rms)
        }
        if (inSpeech) closeRegion()
        if (blockStartSample >= 0) {
            val endAbs = minOf(lastSpeechEndSampleAbs() + padSamples, totalSamples)
            finalizeBlock(endAbs)
        }
        return takeBlocks()
    }

    private fun completeFrame(count: Int, rms: Double) {
        ringPush(frameAccum, count)
        val frameBuffer = if (count == frameSamples) frameAccum else frameAccum.copyOf(count)
        val f = framesFed++
        val speech = rms >= audibleRms
        var skipAppend = false

        if (speech) {
            if (blockStartSample < 0L) {
                openBlock(f)
                skipAppend = true
            }
            if (!inSpeech) {
                inSpeech = true
                regionStartFrame = f
            }
            lastSpeechFrame = f
            silenceRun = 0
        } else {
            silenceRun++
            if (inSpeech && silenceRun >= regionMergeGapFrames) {
                closeRegion()
                inSpeech = false
            }
            if (blockStartSample >= 0L && !inSpeech && silenceRun >= splitSilenceFrames) {
                finalizeBlock(lastSpeechEndSampleAbs() + padSamples)
            }
        }

        if (blockStartSample >= 0L && !skipAppend) {
            builder.addAll(frameBuffer, 0, count)
        }

        // Hard-cut long blocks so a single Groq upload stays far below the API size limit.
        val nextFrameStartAbs = (f + 1) * frameSamples
        if (blockStartSample >= 0L && nextFrameStartAbs - blockStartSample >= maxBlockSamples) {
            hardCut(f)
        }
    }

    /** Opens a block whose audio starts [speechPadSec] before speech frame [firstSpeechFrame]. */
    private fun openBlock(firstSpeechFrame: Long) {
        val frameStartAbs = firstSpeechFrame * frameSamples
        blockStartSample = maxOf(0L, frameStartAbs - padSamples)
        builder.clear()
        regions.clear()
        val needed = ((firstSpeechFrame + 1) * frameSamples - blockStartSample).toInt()
        builder.addAll(ringLastN(needed))
    }

    /** Emits the current block (or discards it when it holds no usable speech region). */
    private fun finalizeBlock(endAbsSample: Long) {
        if (regions.isNotEmpty()) {
            val blockLength = (endAbsSample - blockStartSample).toInt().coerceIn(0, builder.size)
            if (blockLength > 0) {
                emitted += Block(
                    samples = builder.toShortArray(blockLength),
                    sampleRate = sampleRate,
                    startSec = WhisperSegmentCleaner.roundCs(blockStartSample.toDouble() / sampleRate),
                    regions = regions.toList()
                )
            }
        }
        builder.clear()
        regions.clear()
        blockStartSample = -1L
        regionStartFrame = -1L
    }

    /** Ends the current block at the boundary after frame [f] and immediately starts the next one. */
    private fun hardCut(f: Long) {
        val cutSample = (f + 1) * frameSamples
        if (inSpeech) closeRegion()
        finalizeBlock(cutSample)
        blockStartSample = cutSample
        if (inSpeech) {
            // Speech continues across the cut: the next block's first region starts at 0.
            regionStartFrame = f + 1
        }
    }

    private fun closeRegion() {
        if (blockStartSample < 0L || regionStartFrame < 0L || lastSpeechFrame < regionStartFrame) {
            regionStartFrame = -1L
            return
        }
        val startAbs = regionStartFrame * frameSamples
        val endAbs = (lastSpeechFrame + 1) * frameSamples
        val relStart = (startAbs - blockStartSample).toDouble() / sampleRate
        val relEnd = (endAbs - blockStartSample).toDouble() / sampleRate
        if (relEnd - relStart >= minRegionSec) {
            regions += SpeechInterval(
                startSec = WhisperSegmentCleaner.roundCs(relStart),
                endSec = WhisperSegmentCleaner.roundCs(relEnd)
            )
        }
        regionStartFrame = -1L
    }

    private fun lastSpeechEndSampleAbs(): Long = (lastSpeechFrame + 1) * frameSamples

    private fun ringPush(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            ring[ringWrite] = samples[i]
            ringWrite = (ringWrite + 1) % ring.size
            if (ringCount < ring.size) ringCount++
        }
    }

    private fun ringLastN(n: Int): ShortArray {
        val count = n.coerceIn(0, ringCount)
        val out = ShortArray(count)
        val start = (ringWrite - count + ring.size) % ring.size
        for (i in 0 until count) out[i] = ring[(start + i) % ring.size]
        return out
    }

    companion object {
        const val DEFAULT_FRAME_MS = 25L
        const val DEFAULT_MAX_BLOCK_SEC = 240.0
        const val DEFAULT_SPLIT_SILENCE_SEC = 2.5
        const val DEFAULT_REGION_MERGE_GAP_SEC = 0.5
        const val DEFAULT_SPEECH_PAD_SEC = 0.25
        const val DEFAULT_MIN_REGION_SEC = 0.18
        const val DEFAULT_AUDIBLE_RMS = 300.0
    }
}

/**
 * Post-processing for Whisper segments coming back from a VAD-sliced block — the cleanup
 * layer that turns "close enough" Groq timestamps into TurboScribe-grade ones:
 *
 *  - [clampToSpeechRegions] snaps segment edges that leaked into silence back onto the
 *    detected speech regions, and drops segments that sit entirely in silence (the classic
 *    Whisper hallucination over a quiet scene);
 *  - [removeHallucinations] filters credit-roll style phantom lines Whisper loves to invent;
 *  - [coalesceFragments] merges sub-second fragments into readable subtitle lines.
 */
object WhisperSegmentCleaner {

    data class TimedText(val startSec: Double, val endSec: Double, val text: String)

    /** Segments shorter than this are considered unusable slivers. */
    const val MIN_SEGMENT_SEC = 0.12

    private val HALLUCINATION_MARKERS = listOf(
        "字幕由", "字幕提供", "字幕制作", "字幕:", "字幕：", "由amara", "amara.org",
        "subtitles by", "subtitles are", "subtitled by", "captions by", "captioned by",
        "captioning by", "谢谢观看", "感谢观看", "请勿转载", "请不吝", "点赞订阅",
        "时轴", "听译", "校对", "♪", "♫", "الترجمة"
    )

    /**
     * Clamps [segment] to the speech regions of its block. Returns null when the segment
     * does not touch any speech region at all (it was hallucinated over silence).
     */
    fun clampToSpeechRegions(segment: TimedText, regions: List<SpeechInterval>): TimedText? {
        if (segment.endSec <= segment.startSec) return null
        val overlapping = regions.filter {
            it.endSec > segment.startSec + 0.01 && it.startSec < segment.endSec - 0.01
        }
        if (overlapping.isEmpty()) return null
        val start = maxOf(segment.startSec, overlapping.first().startSec)
        val end = minOf(segment.endSec, overlapping.last().endSec)
        if (end - start < MIN_SEGMENT_SEC) return null
        return TimedText(roundCs(start), roundCs(end), segment.text.trim())
    }

    /** True for texts Whisper invents over silence/music (credits, music notes, blanks…). */
    fun isLikelyHallucination(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return true
        if (t.none { it.isLetterOrDigit() }) return true
        return HALLUCINATION_MARKERS.any { t.contains(it) }
    }

    fun removeHallucinations(segments: List<TimedText>): List<TimedText> =
        segments.filter { !isLikelyHallucination(it.text) }

    /**
     * Merges fragments shorter than [minFragmentSec] into an adjacent segment when they are
     * separated by at most [maxJoinGapSec], so one spoken sentence does not become three
     * half-second subtitle rows.
     */
    fun coalesceFragments(
        segments: List<TimedText>,
        minFragmentSec: Double = 0.35,
        maxJoinGapSec: Double = 0.3
    ): List<TimedText> {
        if (segments.size < 2) return segments
        val sorted = segments.sortedBy { it.startSec }
        val out = mutableListOf<TimedText>()

        for (seg in sorted) {
            val prev = out.lastOrNull()
            val prevIsFragment = prev != null && (prev.endSec - prev.startSec) < minFragmentSec
            val gap = if (prev != null) seg.startSec - prev.endSec else Double.MAX_VALUE
            if (prevIsFragment && gap <= maxJoinGapSec) {
                out[out.lastIndex] = prev.copy(
                    endSec = maxOf(prev.endSec, seg.endSec),
                    text = joinText(prev.text, seg.text)
                )
            } else {
                out += seg
            }
        }

        // Merge a short trailing fragment into the line before it.
        val last = out.lastOrNull() ?: return out
        if (out.size >= 2) {
            val before = out[out.size - 2]
            val lastIsFragment = (last.endSec - last.startSec) < minFragmentSec
            if (lastIsFragment && last.startSec - before.endSec <= maxJoinGapSec) {
                out[out.size - 2] = before.copy(
                    endSec = maxOf(before.endSec, last.endSec),
                    text = joinText(before.text, last.text)
                )
                out.removeAt(out.lastIndex)
            }
        }
        return out
    }

    private fun joinText(a: String, b: String): String =
        if (a.isBlank()) b else if (b.isBlank()) a else "$a $b"

    /** Rounds to centiseconds, matching the timestamp precision of TurboScribe's exports. */
    fun roundCs(sec: Double): Double = Math.round(sec * 100.0) / 100.0
}
