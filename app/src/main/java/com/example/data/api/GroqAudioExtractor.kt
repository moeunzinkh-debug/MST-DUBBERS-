package com.example.data.api

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Copies a video's compressed audio track into a small audio-only M4A file for Groq Whisper.
 *
 * This avoids uploading the video's high-bitrate picture stream to an audio transcription API.
 * MediaExtractor/MediaMuxer copy encoded samples without decoding or re-encoding them.
 */
internal object GroqAudioExtractor {
    private const val DEFAULT_SAMPLE_BUFFER_BYTES = 1024 * 1024
    private const val MAX_SAMPLE_BUFFER_BYTES = 16 * 1024 * 1024

    fun extractAudioTrack(videoFile: File): File {
        val outputFile = try {
            File.createTempFile("mst_transcription_audio_", ".m4a", videoFile.parentFile)
        } catch (e: Exception) {
            throw IOException("Could not prepare a temporary audio file: ${e.message}", e)
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var success = false
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track was found in this video")

            extractor.selectTrack(audioTrackIndex)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)
            val muxerInstance = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = muxerInstance
            val outputTrackIndex = muxerInstance.addTrack(audioFormat)
            muxerInstance.start()
            muxerStarted = true

            val declaredBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_SAMPLE_BUFFER_BYTES
            }
            val sampleBuffer = ByteBuffer.allocateDirect(
                declaredBufferSize.coerceAtLeast(DEFAULT_SAMPLE_BUFFER_BYTES).coerceAtMost(MAX_SAMPLE_BUFFER_BYTES)
            )
            val bufferInfo = MediaCodec.BufferInfo()
            var samplesWritten = 0

            while (true) {
                sampleBuffer.clear()
                val sampleSize = extractor.readSampleData(sampleBuffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs >= 0L && sampleSize > 0) {
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    sampleBuffer.position(0)
                    sampleBuffer.limit(sampleSize)
                    bufferInfo.set(0, sampleSize, sampleTimeUs, flags)
                    muxerInstance.writeSampleData(outputTrackIndex, sampleBuffer, bufferInfo)
                    samplesWritten++
                }
                if (!extractor.advance()) break
            }

            if (samplesWritten == 0) {
                throw IOException("No audio samples could be read from this video")
            }

            muxerInstance.stop()
            muxerStarted = false
            muxerInstance.release()
            muxer = null
            if (outputFile.length() <= 0L) {
                throw IOException("The extracted audio file is empty")
            }

            success = true
            return outputFile
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(
                "Could not extract this video's audio for Groq Whisper: ${e.message ?: "unsupported audio format"}. " +
                    "Try Gemini transcription instead.",
                e
            )
        } finally {
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop()
                    } catch (_: Exception) {
                    }
                }
                try {
                    muxer.release()
                } catch (_: Exception) {
                }
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            if (!success) outputFile.delete()
        }
    }
}
