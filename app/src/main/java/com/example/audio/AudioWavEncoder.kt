package com.example.audio

import java.io.ByteArrayOutputStream

/**
 * Encodes processed 16-bit mono PCM samples into a standard RIFF/WAVE ByteArray
 * for direct multimodal submission to the AI Agent.
 */
object AudioWavEncoder {

    fun encodePcm16MonoWav(
        samples: ShortArray,
        sampleRateHz: Int = 16_000
    ): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRateHz * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataByteSize = samples.size * 2
        val totalChunkSize = 36 + dataByteSize

        val out = ByteArrayOutputStream(44 + dataByteSize)
        // RIFF header
        out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        writeIntLE(out, totalChunkSize)
        out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))

        // fmt subchunk
        out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        writeIntLE(out, 16) // PCM subchunk size
        writeShortLE(out, 1) // AudioFormat = 1 (PCM)
        writeShortLE(out, channels)
        writeIntLE(out, sampleRateHz)
        writeIntLE(out, byteRate)
        writeShortLE(out, blockAlign)
        writeShortLE(out, bitsPerSample)

        // data subchunk
        out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        writeIntLE(out, dataByteSize)

        for (sample in samples) {
            val s = sample.toInt()
            out.write(s and 0xFF)
            out.write((s ushr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
}
