package com.example.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Mono 16-bit PCM audio. */
class PcmAudio(val samples: ShortArray, val sampleRate: Int) {
    val durationSec: Double get() = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0
}

/** A growable ShortArray, to collect decoded samples without boxing. */
internal class ShortArrayBuilder(initialCapacity: Int = 16 * 1024) {
    private var data = ShortArray(initialCapacity.coerceAtLeast(16))
    var size = 0
        private set

    fun add(value: Short) {
        ensureCapacity(size + 1)
        data[size++] = value
    }

    fun addAll(values: ShortArray, offset: Int = 0, length: Int = values.size - offset) {
        ensureCapacity(size + length)
        System.arraycopy(values, offset, data, size, length)
        size += length
    }

    fun toShortArray(): ShortArray = data.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newSize = data.size * 2
        while (newSize < required) newSize *= 2
        data = data.copyOf(newSize)
    }
}

/** Decodes TTS clips (MP3, AAC, WAV, ...) into mono 16-bit PCM. */
object AudioClipDecoder {

    /** Gives up on a clip whose decoder makes no progress for this long. */
    private const val STALL_TIMEOUT_MS = 10_000L
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Decodes [file] to mono PCM. The format is detected from the content, not the file name
     * (the Gemini TTS fallback saves WAV data under a `.mp3` name).
     */
    fun decodeToMono(file: File): PcmAudio {
        if (!file.exists()) throw IOException("Audio file not found: ${file.name}")
        WavCodec.readPcm16(file)?.let { return it }
        return decodeWithMediaCodec(file)
    }

    private fun decodeWithMediaCodec(file: File): PcmAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${file.name}")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Unknown audio format in ${file.name}")

            var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channelCount = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val output = ShortArrayBuilder()

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastProgressAt = System.currentTimeMillis()
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex)
                                ?: throw IOException("Decoder returned no input buffer")
                            val size = extractor.readSampleData(inBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            lastProgressAt = System.currentTimeMillis()
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                    when {
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                val outBuffer = codec.getOutputBuffer(outIndex)
                                    ?: throw IOException("Decoder returned no output buffer")
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                appendAsMono(outBuffer, pcmEncoding, channelCount, output)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                            lastProgressAt = System.currentTimeMillis()
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channelCount = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                            pcmEncoding = format.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        }
                    }

                    if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                        throw IOException("Decoding ${file.name} timed out")
                    }
                }
            } finally {
                try {
                    codec.stop()
                } catch (_: Exception) {
                }
                codec.release()
            }

            if (sampleRate <= 0) throw IOException("Unknown sample rate in ${file.name}")
            return PcmAudio(output.toShortArray(), sampleRate)
        } finally {
            extractor.release()
        }
    }

    /** Appends interleaved decoder output (16-bit or float) to [out], averaged down to mono. */
    private fun appendAsMono(buffer: ByteBuffer, encoding: Int, channelCount: Int, out: ShortArrayBuilder) {
        val channels = channelCount.coerceAtLeast(1)
        val data = buffer.slice().order(ByteOrder.nativeOrder())
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = data.asFloatBuffer()
            val frames = floats.remaining() / channels
            for (frame in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += floats.get(frame * channels + c)
                out.add(((sum / channels).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
            }
        } else {
            val shorts = data.asShortBuffer()
            val frames = shorts.remaining() / channels
            for (frame in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += shorts.get(frame * channels + c)
                out.add((sum / channels).toShort())
            }
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}

/** Minimal reader/writer for 16-bit PCM WAV files. */
object WavCodec {
    const val HEADER_SIZE = 44

    /**
     * Reads a 16-bit PCM WAV file as mono. Returns null when [file] is not a WAV file or uses an
     * encoding this reader doesn't handle (the caller then falls back to MediaCodec).
     */
    fun readPcm16(file: File): PcmAudio? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return null
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null

            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var formatTag = 0
            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val bodyStart = raf.filePointer
                when (id) {
                    "fmt " -> {
                        if (size < 16) return null
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        formatTag = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byte rate
                        bb.short // block align
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        // 1 = PCM, 0xFFFE = WAVE_FORMAT_EXTENSIBLE (PCM sub-format in practice).
                        if ((formatTag != 1 && formatTag != 0xFFFE) || bitsPerSample != 16 ||
                            channels <= 0 || sampleRate <= 0
                        ) return null
                        // Streamed WAVs may declare a bogus size: trust the file length instead.
                        val available = raf.length() - bodyStart
                        val dataSize = if (size in 1..available) size else available
                        val frameBytes = 2 * channels
                        val frames = (dataSize / frameBytes).toInt()
                        val bytes = ByteArray(frames * frameBytes)
                        raf.readFully(bytes)
                        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val mono = ShortArray(frames)
                        for (frame in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += shorts.get(frame * channels + c)
                            mono[frame] = (sum / channels).toShort()
                        }
                        return PcmAudio(mono, sampleRate)
                    }
                }
                // Chunks are padded to an even size.
                raf.seek(bodyStart + size + (size and 1L))
            }
            return null
        }
    }

    /** Writes the 44-byte header of a 16-bit PCM WAV file with [dataBytes] bytes of samples. */
    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        }.array()
    }
}
