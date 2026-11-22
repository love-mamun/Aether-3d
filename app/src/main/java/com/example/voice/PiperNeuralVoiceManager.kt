package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.example.ai.AssistantPersona
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "PiperNeuralVoice"

data class NeuralVoicePack(
    val id: String,
    val displayName: String,
    val languageTag: String,
    val genderHint: String,
    val downloadSizeMb: Float,
    val downloadUrl: String,
    val extractedFolderName: String,
    val defaultSpeakerId: Int = 0
)

enum class VoicePackStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

data class VoicePackUiState(
    val pack: NeuralVoicePack,
    val status: VoicePackStatus = VoicePackStatus.NOT_INSTALLED,
    val progressPercent: Int = 0,
    val errorMessage: String? = null
)

/**
 * Manages on-demand ultra-compact (< 25 MB compressed) Sherpa-ONNX Piper VITS neural voice packs
 * stored in app internal storage and streams synthesized PCM via USAGE_VOICE_COMMUNICATION
 * AudioTrack so hardware AcousticEchoCanceler subtracts speaker output seamlessly.
 */
class PiperNeuralVoiceManager(context: Context) {

    private val appContext = context.applicationContext
    private val voicesRootDir = File(appContext.filesDir, "voices").apply { mkdirs() }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private var activePackId: String? = null
    private var offlineTts: OfflineTts? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var stopRequested = false

    val catalog: List<NeuralVoicePack> = listOf(
        NeuralVoicePack(
            id = "en_US-lessac-medium-int8",
            displayName = "Lessac Studio (English Female · Medium)",
            languageTag = "en-US",
            genderHint = "Female",
            downloadSizeMb = 20.0f,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium-int8.tar.bz2",
            extractedFolderName = "vits-piper-en_US-lessac-medium-int8"
        ),
        NeuralVoicePack(
            id = "en_US-ryan-medium-int8",
            displayName = "Ryan Neural (English Male · Medium)",
            languageTag = "en-US",
            genderHint = "Male",
            downloadSizeMb = 20.1f,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-medium-int8.tar.bz2",
            extractedFolderName = "vits-piper-en_US-ryan-medium-int8"
        ),
        NeuralVoicePack(
            id = "en_US-amy-low-int8",
            displayName = "Amy Crisp (English Warm · Lite)",
            languageTag = "en-US",
            genderHint = "Female",
            downloadSizeMb = 20.1f,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low-int8.tar.bz2",
            extractedFolderName = "vits-piper-en_US-amy-low-int8"
        )
    )

    private val _packStates = MutableStateFlow<List<VoicePackUiState>>(emptyList())
    val packStates: StateFlow<List<VoicePackUiState>> = _packStates.asStateFlow()

    init {
        refreshInstalledStates()
    }

    fun refreshInstalledStates() {
        _packStates.value = catalog.map { pack ->
            val ready = isPackInstalled(pack)
            VoicePackUiState(
                pack = pack,
                status = if (ready) VoicePackStatus.READY else VoicePackStatus.NOT_INSTALLED,
                progressPercent = if (ready) 100 else 0
            )
        }
    }

    fun pickBestInstalledPack(
        preferredId: String,
        persona: AssistantPersona
    ): NeuralVoicePack? {
        val preferred = catalog.firstOrNull { it.id == preferredId && isPackInstalled(it) }
        if (preferred != null) return preferred
        val personaPreferredId = when (persona) {
            AssistantPersona.MAYRA -> "en_US-lessac-medium-int8"
            AssistantPersona.JARVIS,
            AssistantPersona.MAX,
            AssistantPersona.STEALTH -> "en_US-ryan-medium-int8"
        }
        return catalog.firstOrNull { it.id == personaPreferredId && isPackInstalled(it) }
            ?: catalog.firstOrNull { isPackInstalled(it) }
    }

    fun isPackInstalled(pack: NeuralVoicePack): Boolean {
        val dir = packDir(pack)
        if (!dir.exists()) return false
        return findOnnxFile(dir) != null && findTokensFile(dir) != null
    }

    private fun packDir(pack: NeuralVoicePack): File = File(voicesRootDir, pack.id)

    private fun findOnnxFile(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx") }

    private fun findTokensFile(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isFile && it.name == "tokens.txt" }

    private fun findEspeakDataDir(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }

    suspend fun downloadAndInstallPack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val pack = catalog.firstOrNull { it.id == packId }
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown voice pack: $packId"))

        updatePackState(packId) {
            it.copy(status = VoicePackStatus.DOWNLOADING, progressPercent = 0, errorMessage = null)
        }

        val tempArchive = File(appContext.cacheDir, "${pack.id}.tar.bz2")
        val targetDir = packDir(pack)

        try {
            val request = Request.Builder().url(pack.downloadUrl).build()
            downloadClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Download HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("Empty voice pack response")
                val totalBytes = body.contentLength().coerceAtLeast(1L)
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)
                var lastPct = -1

                body.byteStream().use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                updatePackState(packId) {
                                    it.copy(status = VoicePackStatus.DOWNLOADING, progressPercent = pct)
                                }
                            }
                        }
                    }
                }
            }

            updatePackState(packId) {
                it.copy(status = VoicePackStatus.EXTRACTING, progressPercent = 99)
            }

            targetDir.deleteRecursively()
            targetDir.mkdirs()

            extractTarBz2Safely(tempArchive, targetDir)

            if (!isPackInstalled(pack)) {
                throw IllegalStateException("Extracted archive missing ONNX model or tokens.txt")
            }

            updatePackState(packId) {
                it.copy(status = VoicePackStatus.READY, progressPercent = 100, errorMessage = null)
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading Piper voice ${pack.id}", e)
            targetDir.deleteRecursively()
            updatePackState(packId) {
                it.copy(
                    status = VoicePackStatus.ERROR,
                    progressPercent = 0,
                    errorMessage = e.localizedMessage ?: "Download failed"
                )
            }
            Result.failure(e)
        } finally {
            runCatching { tempArchive.delete() }
        }
    }

    private fun extractTarBz2Safely(archive: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath + File.separator
        BufferedInputStream(FileInputStream(archive)).use { buffered ->
            BZip2CompressorInputStream(buffered).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextEntry
                    val buf = ByteArray(16 * 1024)
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                            throw SecurityException("Blocked unsafe tar path: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                while (true) {
                                    val len = tar.read(buf)
                                    if (len <= 0) break
                                    out.write(buf, 0, len)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    fun deletePack(packId: String) {
        val pack = catalog.firstOrNull { it.id == packId } ?: return
        if (activePackId == packId) {
            runCatching { offlineTts?.release() }
            offlineTts = null
            activePackId = null
        }
        packDir(pack).deleteRecursively()
        refreshInstalledStates()
    }

    private fun ensureEngineLoaded(pack: NeuralVoicePack): OfflineTts? {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return null
        }
        if (activePackId == pack.id && offlineTts != null) {
            return offlineTts
        }
        val dir = packDir(pack)
        val modelFile = findOnnxFile(dir) ?: return null
        val tokensFile = findTokensFile(dir) ?: return null
        val dataDir = findEspeakDataDir(dir)?.absolutePath.orEmpty()

        return runCatching {
            offlineTts?.release()
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                lexicon = "",
                tokens = tokensFile.absolutePath,
                dataDir = dataDir,
                dictDir = "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val ttsConfig = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.22f
            )
            OfflineTts(assetManager = null, config = ttsConfig).also {
                offlineTts = it
                activePackId = pack.id
                Log.i(TAG, "Initialized Piper OfflineTts '${pack.id}' @ ${it.sampleRate()} Hz")
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed initializing Sherpa-ONNX OfflineTts", err)
        }.getOrNull()
    }

    suspend fun speakWithPack(
        text: String,
        pack: NeuralVoicePack,
        speed: Float,
        audioAttributes: AudioAttributes,
        onAmplitude: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val engine = ensureEngineLoaded(pack) ?: return@withContext false
        stopRequested = false
        val sampleRate = engine.sampleRate().coerceIn(8000, 48000)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeTrack = track
        try {
            track.play()
            engine.generateWithCallback(
                text = text,
                sid = pack.defaultSpeakerId,
                speed = speed.coerceIn(0.75f, 1.35f)
            ) { floatSamples ->
                if (stopRequested) {
                    return@generateWithCallback 0
                }
                if (floatSamples.isNotEmpty()) {
                    val pcm = ShortArray(floatSamples.size)
                    var sumSq = 0.0
                    for (i in floatSamples.indices) {
                        val clamped = floatSamples[i].coerceIn(-1f, 1f)
                        val s = (clamped * 32767f).toInt().toShort()
                        pcm[i] = s
                        sumSq += clamped * clamped
                    }
                    val rms = sqrt(sumSq / floatSamples.size).toFloat()
                    onAmplitude((rms * 2.6f).coerceIn(0.08f, 0.96f))
                    val written = runCatching {
                        track.write(pcm, 0, pcm.size)
                    }.getOrDefault(-1)
                    if (written < 0 || stopRequested) {
                        return@generateWithCallback 0
                    }
                }
                1
            }
            if (!stopRequested) {
                runCatching {
                    track.stop()
                }
            }
            !stopRequested
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Piper playback failed", e)
            false
        } finally {
            onAmplitude(0f)
            runCatching { track.release() }
            if (activeTrack === track) {
                activeTrack = null
            }
        }
    }

    fun stopPlayback() {
        stopRequested = true
        runCatching {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.stop()
        }
    }

    fun release() {
        stopPlayback()
        runCatching { offlineTts?.release() }
        offlineTts = null
        activePackId = null
    }

    private fun updatePackState(
        packId: String,
        transform: (VoicePackUiState) -> VoicePackUiState
    ) {
        _packStates.update { current ->
            current.map { if (it.pack.id == packId) transform(it) else it }
        }
    }
}
