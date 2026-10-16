package com.example.util

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavHelper {

    fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int = 24000,
        channels: Short = 1,
        bitsPerSample: Short = 16
    ): ByteArray {
        val totalAudioLen = pcm.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample) / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            // RIFF chunk descriptor
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(totalDataLen)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())

            // fmt sub-chunk
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // Subchunk1Size for PCM
            putShort(1) // AudioFormat 1 = PCM
            putShort(channels)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample)

            // data sub-chunk
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(totalAudioLen)
        }.array()

        val wav = ByteArray(44 + pcm.size)
        System.arraycopy(header, 0, wav, 0, 44)
        System.arraycopy(pcm, 0, wav, 44, pcm.size)
        return wav
    }

    fun saveWavFile(file: File, pcm: ByteArray, sampleRate: Int = 24000): Double {
        val wavBytes = pcmToWav(pcm, sampleRate)
        FileOutputStream(file).use { it.write(wavBytes) }
        val byteRate = (sampleRate * 1 * 16) / 8.0
        return pcm.size / byteRate
    }
}
