package com.example.data

import com.example.data.api.TtsException
import com.example.data.db.SegmentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

/** Progress of a voice generation run. */
data class VoiceBatchProgress(
    val done: Int,
    val total: Int,
    val voiced: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** Seconds left before the current line is tried again, while waiting. */
    val retryInSec: Int? = null
)

/** Result of a voice generation run. */
data class VoiceBatchOutcome(
    val voiced: List<Long>,
    /** Lines with nothing to read (only symbols), with the reason. */
    val skipped: Map<Long, String>,
    /** Lines that still failed after all attempts, with the reason. */
    val failed: Map<Long, String>,
    /** True if the run gave up early because many lines in a row failed. */
    val stoppedEarly: Boolean
)

/**
 * Generates the AI voice of many lines, one after the other.
 *
 * Unlike a plain loop, one bad line no longer stops the whole run:
 * - a line with no words to read (only ♪, ... and similar) is skipped;
 * - a temporary failure (server busy, timeout, too many requests) is retried after a short wait;
 * - a line that still fails is recorded and the run moves on to the next line.
 * The run only stops early when [maxConsecutiveFailures] lines in a row fail, which usually
 * means that the server or the connection is down.
 */
class VoiceBatchRunner(
    private val maxAttempts: Int = 3,
    private val maxConsecutiveFailures: Int = 4,
    /** Wait before attempt number `n + 1` (n starts at 1) when the server didn't say how long. */
    private val backoffMs: (attempt: Int) -> Long = { attempt -> if (attempt <= 1) 2_000L else 6_000L },
    /** Longest wait honoured when the server asks to wait (Retry-After). */
    private val maxWaitMs: Long = 30_000L,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {

    /**
     * Voices [segments] in order. [generate] makes the voice of one line; `lastAttempt` tells it
     * whether this is the last try for the line (when a backup engine may be used).
     */
    suspend fun run(
        segments: List<SegmentEntity>,
        generate: suspend (segment: SegmentEntity, lastAttempt: Boolean) -> Unit,
        onLineDone: (segment: SegmentEntity, error: TtsException?) -> Unit = { _, _ -> },
        onProgress: (VoiceBatchProgress) -> Unit = {}
    ): VoiceBatchOutcome {
        val voiced = mutableListOf<Long>()
        val skipped = linkedMapOf<Long, String>()
        val failed = linkedMapOf<Long, String>()
        var consecutiveFailures = 0
        var stoppedEarly = false
        val total = segments.size

        fun progress(done: Int, retryInSec: Int? = null) = VoiceBatchProgress(
            done = done,
            total = total,
            voiced = voiced.size,
            skipped = skipped.size,
            failed = failed.size,
            retryInSec = retryInSec
        )

        onProgress(progress(0))
        for ((index, segment) in segments.withIndex()) {
            var attempt = 1
            while (true) {
                currentCoroutineContext().ensureActive()
                val error = try {
                    generate(segment, attempt >= maxAttempts)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TtsException.from(e)
                }

                if (error == null) {
                    voiced += segment.id
                    consecutiveFailures = 0
                    onLineDone(segment, null)
                    break
                }
                if (error.kind == TtsException.Kind.NOTHING_TO_SPEAK) {
                    skipped[segment.id] = error.message ?: "Nothing to read"
                    onLineDone(segment, error)
                    break
                }
                if (error.isRetryable && attempt < maxAttempts) {
                    val waitMs = error.retryAfterSec?.let { (it * 1000L).coerceIn(1_000L, maxWaitMs) }
                        ?: backoffMs(attempt)
                    onProgress(progress(index, retryInSec = ceil(waitMs / 1000.0).toInt()))
                    sleep(waitMs)
                    attempt++
                    continue
                }
                failed[segment.id] = error.message ?: "Unknown error"
                consecutiveFailures++
                onLineDone(segment, error)
                break
            }
            onProgress(progress(index + 1))
            if (consecutiveFailures >= maxConsecutiveFailures && index < segments.lastIndex) {
                stoppedEarly = true
                break
            }
        }
        return VoiceBatchOutcome(voiced, skipped, failed, stoppedEarly)
    }
}
