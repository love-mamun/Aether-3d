package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

data class VoiceSampleAnalysis(
    val sampleRateHz: Int,
    val channels: Int,
    val durationSeconds: Float,
    val estimatedPitchHz: Float,
    val calibratedPitchFactor: Float,
    val summaryLabel: String
)

data class ImportedVoiceSampleResult(
    val savedPath: String,
    val fileName: String,
    val analysis: VoiceSampleAnalysis
)

object VoiceSampleAnalyzer {

    /**
     * Parses a RIFF/WAVE PCM-16 stream in-memory, measures voiced fundamental pitch F0 via
     * normalized autocorrelation (75 Hz - 300 Hz), and derives a natural pitch multiplier for
     * Aether's offline neural / system voice.
     */
    fun analyzeWavBytes(wavBytes: ByteArray): VoiceSampleAnalysis {
        if (wavBytes.size < 44 ||
            wavBytes[0] != 'R'.code.toByte() ||
            wavBytes[1] != 'I'.code.toByte() ||
            wavBytes[2] != 'F'.code.toByte() ||
            wavBytes[3] != 'F'.code.toByte()
        ) {
            return VoiceSampleAnalysis(
                sampleRateHz = 16_000,
                channels = 1,
                durationSeconds = 0f,
                estimatedPitchHz = 128f,
                calibratedPitchFactor = 1.0f,
                summaryLabel = "Audio sample saved (default pitch 1.00x)"
            )
        }

        val bb = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 1
        var sampleRate = 16_000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLength = 0

        var pos = 12
        while (pos + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(pos + 4).coerceAtLeast(0)
            val bodyStart = pos + 8
            if (chunkId == "fmt " && bodyStart + 16 <= wavBytes.size) {
                channels = bb.getShort(bodyStart + 2).toInt().coerceIn(1, 2)
                sampleRate = bb.getInt(bodyStart + 4).coerceIn(8_000, 96_000)
                bitsPerSample = bb.getShort(bodyStart + 14).toInt().coerceIn(8, 32)
            } else if (chunkId == "data") {
                dataOffset = bodyStart
                dataLength = chunkSize.coerceAtMost(wavBytes.size - bodyStart)
                break
            }
            val padded = chunkSize + (chunkSize and 1)
            pos = bodyStart + padded
        }

        if (dataOffset < 0 || dataLength < 64 || bitsPerSample != 16) {
            return VoiceSampleAnalysis(
                sampleRateHz = sampleRate,
                channels = channels,
                durationSeconds = 0f,
                estimatedPitchHz = 128f,
                calibratedPitchFactor = 1.0f,
                summaryLabel = "Audio sample loaded (${sampleRate / 1000}kHz)"
            )
        }

        val bytesPerFrame = channels * 2
        val totalFrames = (dataLength / bytesPerFrame).coerceAtLeast(1)
        val durationSeconds = totalFrames.toFloat() / sampleRate.toFloat()

        // Downsample up to first 12 seconds into mono float buffer (~16kHz max) for fast autocorrelation
        val step = (sampleRate / 16_000).coerceAtLeast(1)
        val maxAnalyzeFrames = minOf(totalFrames, sampleRate * 12)
        val mono = FloatArray(maxAnalyzeFrames / step)
        var outIdx = 0
        var frameIdx = 0
        while (frameIdx + step <= maxAnalyzeFrames && outIdx < mono.size) {
            val bytePos = dataOffset + frameIdx * bytesPerFrame
            val sample = if (channels == 1) {
                bb.getShort(bytePos) / 32768f
            } else {
                ((bb.getShort(bytePos).toInt() + bb.getShort(bytePos + 2).toInt()) * 0.5f) / 32768f
            }
            mono[outIdx++] = sample
            frameIdx += step
        }

        val effectiveRate = sampleRate.toFloat() / step.toFloat()
        val f0 = estimatePitchHz(mono, outIdx, effectiveRate)
        // Calibrate against baseline neural voice (~138 Hz mid-male / 165 Hz starter)
        val baselineF0 = 138f
        val pitchFactor = (f0 / baselineF0).coerceIn(0.80f, 1.22f)
        val roundedDuration = String.format(Locale.US, "%.1fs", durationSeconds)
        val summary = "Calibrated ${f0.toInt()} Hz · ${String.format(Locale.US, "%.2fx", pitchFactor)} · $roundedDuration"

        return VoiceSampleAnalysis(
            sampleRateHz = sampleRate,
            channels = channels,
            durationSeconds = durationSeconds,
            estimatedPitchHz = f0,
            calibratedPitchFactor = pitchFactor,
            summaryLabel = summary
        )
    }

    private fun estimatePitchHz(samples: FloatArray, length: Int, sampleRate: Float): Float {
        val windowSize = (sampleRate * 0.040f).toInt().coerceIn(256, 1024)
        val hop = windowSize / 2
        if (length < windowSize) return 128f

        val minLag = (sampleRate / 300f).toInt().coerceAtLeast(20)
        val maxLag = (sampleRate / 75f).toInt().coerceAtMost(windowSize - 2)
        val pitchEstimates = mutableListOf<Float>()

        var start = 0
        while (start + windowSize <= length && pitchEstimates.size < 45) {
            var energy = 0.0
            for (i in 0 until windowSize) {
                val s = samples[start + i]
                energy += s * s
            }
            val rms = sqrt(energy / windowSize)
            if (rms > 0.015) {
                var bestLag = -1
                var bestCorr = 0.35
                for (lag in minLag..maxLag) {
                    var num = 0.0
                    var denA = 0.0
                    var denB = 0.0
                    val limit = windowSize - lag
                    for (i in 0 until limit) {
                        val a = samples[start + i].toDouble()
                        val b = samples[start + i + lag].toDouble()
                        num += a * b
                        denA += a * a
                        denB += b * b
                    }
                    val denom = sqrt(denA * denB)
                    if (denom > 1e-6) {
                        val corr = num / denom
                        if (corr > bestCorr) {
                            bestCorr = corr
                            bestLag = lag
                        }
                    }
                }
                if (bestLag > 0) {
                    val hz = sampleRate / bestLag.toFloat()
                    if (hz in 75f..295f) {
                        pitchEstimates.add(hz)
                    }
                }
            }
            start += hop
        }

        if (pitchEstimates.isEmpty()) return 128f
        pitchEstimates.sort()
        return pitchEstimates[pitchEstimates.size / 2]
    }

    suspend fun importVoiceSampleFromUri(
        context: Context,
        uri: Uri
    ): Result<ImportedVoiceSampleResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolveDisplayName(context, uri) ?: "user_voice_sample.wav"
            val ext = displayName.substringAfterLast('.', "wav").lowercase(Locale.US)
                .takeIf { it in setOf("wav", "mp3", "m4a", "aac", "ogg") } ?: "wav"

            val voicesDir = File(context.filesDir, "voices").apply { mkdirs() }
            val destFile = File(voicesDir, "user_aether_sample.$ext")

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            } ?: throw IllegalStateException("Cannot open selected voice file.")

            if (destFile.length() < 128L) {
                throw IllegalStateException("Selected voice file appears empty.")
            }

            val analysis = if (ext == "wav") {
                val headBytes = destFile.inputStream().use { stream ->
                    val maxRead = minOf(destFile.length(), 1_200_000L).toInt()
                    val buf = ByteArray(maxRead)
                    var readTotal = 0
                    while (readTotal < maxRead) {
                        val n = stream.read(buf, readTotal, maxRead - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    buf.copyOf(readTotal)
                }
                analyzeWavBytes(headBytes)
            } else {
                VoiceSampleAnalysis(
                    sampleRateHz = 44_100,
                    channels = 1,
                    durationSeconds = 0f,
                    estimatedPitchHz = 132f,
                    calibratedPitchFactor = 0.98f,
                    summaryLabel = "Sample '$displayName' saved for Aether"
                )
            }

            ImportedVoiceSampleResult(
                savedPath = destFile.absolutePath,
                fileName = displayName,
                analysis = analysis
            )
        }
    }

    suspend fun playWavPreview(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.name.endsWith(".wav", ignoreCase = true)) return@withContext false
        val bytes = runCatching {
            val maxBytes = minOf(file.length(), 640_000L).toInt()
            file.inputStream().use { stream ->
                val buf = ByteArray(maxBytes)
                var readTotal = 0
                while (readTotal < maxBytes) {
                    val n = stream.read(buf, readTotal, maxBytes - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                buf.copyOf(readTotal)
            }
        }.getOrNull() ?: return@withContext false

        if (bytes.size < 44) return@withContext false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 1
        var sampleRate = 16_000
        var dataOffset = 44
        var dataLength = bytes.size - 44

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(pos + 4).coerceAtLeast(0)
            val bodyStart = pos + 8
            if (chunkId == "fmt " && bodyStart + 16 <= bytes.size) {
                channels = bb.getShort(bodyStart + 2).toInt().coerceIn(1, 2)
                sampleRate = bb.getInt(bodyStart + 4).coerceIn(8_000, 48_000)
            } else if (chunkId == "data") {
                dataOffset = bodyStart
                dataLength = chunkSize.coerceAtMost(bytes.size - bodyStart)
                break
            }
            pos = bodyStart + chunkSize + (chunkSize and 1)
        }

        if (dataLength <= 64) return@withContext false
        val chMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(chMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        return@withContext runCatching {
            track.play()
            track.write(bytes, dataOffset, dataLength)
            Thread.sleep(200L)
            track.stop()
            track.release()
            true
        }.getOrElse {
            runCatching { track.release() }
            false
        }
    }

    fun resolveDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
