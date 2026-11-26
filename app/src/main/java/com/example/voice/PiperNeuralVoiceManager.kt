package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.ai.AssistantPersona
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "PiperNeuralVoice"
internal const val BUNDLED_STARTER_VOICE_ID = "en_US-amy-low-int8"
internal const val CUSTOM_AETHER_VOICE_ID = "custom_aether_onnx"

data class NeuralVoicePack(
    val id: String,
    val displayName: String,
    val languageTag: String,
    val genderHint: String,
    val downloadSizeMb: Float,
    val downloadUrl: String,
    val extractedFolderName: String,
    val defaultSpeakerId: Int = 0,
    val isBundledAsset: Boolean = false,
    val bundledAssetPath: String? = null
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

internal object PiperAudioUtils {
    /**
     * Converts normalized float PCM samples [-1f..1f] to little-endian 16-bit PCM bytes
     * while computing normalized RMS amplitude [0f..1f] for waveform/orb animation.
     */
    fun floatFrameToPcm16(
        samples: FloatArray,
        offset: Int,
        length: Int,
        outBytes: ByteArray
    ): Float {
        var sumSq = 0.0
        val count = length.coerceAtLeast(1)
        for (i in 0 until length) {
            val clipped = samples[offset + i].coerceIn(-1.0f, 1.0f)
            sumSq += clipped * clipped
            val s16 = (clipped * 32767.0f).toInt().coerceIn(-32768, 32767)
            val byteIdx = i * 2
            outBytes[byteIdx] = (s16 and 0xFF).toByte()
            outBytes[byteIdx + 1] = ((s16 ushr 8) and 0xFF).toByte()
        }
        val rms = sqrt(sumSq / count).toFloat()
        return (rms * 2.6f).coerceIn(0.08f, 1.0f)
    }

    fun splitSentencesForPipeline(text: String): List<String> {
        val raw = text.trim()
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(Regex("(?<=[.!?।\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (parts.isEmpty()) listOf(raw) else parts
    }

    fun selectBestPackForLanguage(
        catalog: List<NeuralVoicePack>,
        preferredId: String,
        persona: AssistantPersona,
        preferBangla: Boolean,
        isInstalled: (NeuralVoicePack) -> Boolean
    ): NeuralVoicePack? {
        val targetLangPrefix = if (preferBangla) "bn" else "en"
        val matchingInstalled = catalog.filter {
            it.languageTag.lowercase().startsWith(targetLangPrefix) && isInstalled(it)
        }
        if (matchingInstalled.isEmpty()) return null

        val exactPreferred = matchingInstalled.firstOrNull { it.id == preferredId }
        if (exactPreferred != null) return exactPreferred

        if (preferBangla) {
            val femaleFirst = persona == AssistantPersona.ARIA
            return if (femaleFirst) {
                matchingInstalled.firstOrNull { it.genderHint.equals("Female", ignoreCase = true) }
                    ?: matchingInstalled.first()
            } else {
                matchingInstalled.firstOrNull { it.id.contains("mimic3", ignoreCase = true) }
                    ?: matchingInstalled.first()
            }
        }

        val personaPreferredIds = when (persona) {
            AssistantPersona.ARIA -> listOf("en_US-lessac-medium-int8", BUNDLED_STARTER_VOICE_ID)
            AssistantPersona.AETHER -> listOf(
                CUSTOM_AETHER_VOICE_ID,
                "en_US-ryan-medium-int8",
                BUNDLED_STARTER_VOICE_ID
            )
        }
        for (candidateId in personaPreferredIds) {
            matchingInstalled.firstOrNull { it.id == candidateId }?.let { return it }
        }
        return matchingInstalled.firstOrNull()
    }
}

/**
 * Manages bundled and on-demand Sherpa-ONNX Piper / VITS neural voice packs stored in app internal
 * storage and synthesizes PCM audio locally using native OfflineTts + AudioTrack.
 * Gracefully reports unavailability on unsupported ABIs (e.g. x86_64 emulators) so callers fall back
 * cleanly to Android System TTS.
 */
class PiperNeuralVoiceManager(context: Context) {

    private val appContext = context.applicationContext
    private val voicesRootDir = File(appContext.filesDir, "voices").apply { mkdirs() }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ttsMutex = Mutex()
    private val extractMutex = Mutex()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var loadedTts: OfflineTts? = null
    private var activePackId: String? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var nativeBinaryUnavailable = false

    val catalog: List<NeuralVoicePack> = listOf(
        NeuralVoicePack(
            id = BUNDLED_STARTER_VOICE_ID,
            displayName = "Amy Neural (English Female · Download)",
            languageTag = "en-US",
            genderHint = "Female",
            downloadSizeMb = 20.1f,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low-int8.tar.bz2",
            extractedFolderName = "vits-piper-en_US-amy-low-int8",
            isBundledAsset = false,
            bundledAssetPath = null
        ),
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
            id = "bn_BD-mimic3-multi_low",
            displayName = "বাংলা Mimic3 Neural (Bangla · Offline VITS)",
            languageTag = "bn-BD",
            genderHint = "Multi-Speaker",
            downloadSizeMb = 76.2f,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-bn-multi_low.tar.bz2",
            extractedFolderName = "vits-mimic3-bn-multi_low",
            defaultSpeakerId = 0
        ),
        NeuralVoicePack(
            id = "bn_BD-coqui-female",
            displayName = "বাংলা Coqui Studio (Bangla Female · HD)",
            languageTag = "bn-BD",
            genderHint = "Female",
            downloadSizeMb = 103.0f,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-coqui-bn-custom_female.tar.bz2",
            extractedFolderName = "vits-coqui-bn-custom_female",
            defaultSpeakerId = 0
        ),
        NeuralVoicePack(
            id = CUSTOM_AETHER_VOICE_ID,
            displayName = "Custom Aether Neural (.onnx Import)",
            languageTag = "en-US",
            genderHint = "Custom",
            downloadSizeMb = 0f,
            downloadUrl = "",
            extractedFolderName = CUSTOM_AETHER_VOICE_ID,
            defaultSpeakerId = 0
        )
    )

    private val _packStates = MutableStateFlow<List<VoicePackUiState>>(emptyList())
    val packStates: StateFlow<List<VoicePackUiState>> = _packStates.asStateFlow()

    private val _engineStatusLabel = MutableStateFlow(detectInitialEngineLabel())
    val engineStatusLabel: StateFlow<String> = _engineStatusLabel.asStateFlow()

    init {
        refreshInstalledStates()
    }

    private fun detectInitialEngineLabel(): String {
        val supported = runCatching {
            Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }
        }.getOrDefault(false)
        val installedCount = catalog.count { isPackInstalled(it) }
        return when {
            !supported -> "System TTS (${Build.SUPPORTED_ABIS.firstOrNull() ?: "emulator"})"
            installedCount > 0 -> "Piper Neural ($installedCount pack${if (installedCount > 1) "s" else ""}) + System TTS"
            else -> "System TTS Active · Neural Packs In-App"
        }
    }

    fun isNativeRuntimeLikelySupported(): Boolean = !nativeBinaryUnavailable &&
        Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }

    fun computeInstalledDiskMb(): Float {
        return catalog.filter { isPackInstalled(it) }
            .sumOf { pack ->
                val dir = packDir(pack)
                val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                bytes.toDouble()
            }.toFloat() / (1024f * 1024f)
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
        _engineStatusLabel.value = detectInitialEngineLabel()
    }

    suspend fun ensureBundledStarterInstalled(): Boolean {
        val starter = catalog.firstOrNull { it.isBundledAsset && !it.bundledAssetPath.isNullOrBlank() }
            ?: return false
        if (isPackInstalled(starter)) return true

        return extractMutex.withLock {
            if (isPackInstalled(starter)) return@withLock true
            val assetPath = starter.bundledAssetPath ?: return@withLock false
            val targetDir = packDir(starter)
            withContext(Dispatchers.IO) {
                try {
                    updatePackState(starter.id) {
                        it.copy(status = VoicePackStatus.EXTRACTING, progressPercent = 85, errorMessage = null)
                    }
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    appContext.assets.open(assetPath).use { assetStream ->
                        extractTarBz2StreamSafely(assetStream, targetDir)
                    }
                    val ok = isPackInstalled(starter)
                    updatePackState(starter.id) {
                        if (ok) {
                            it.copy(status = VoicePackStatus.READY, progressPercent = 100, errorMessage = null)
                        } else {
                            it.copy(
                                status = VoicePackStatus.ERROR,
                                progressPercent = 0,
                                errorMessage = "Bundled model incomplete"
                            )
                        }
                    }
                    ok
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Bundled starter extraction deferred/failed: ${t.message}")
                    targetDir.deleteRecursively()
                    updatePackState(starter.id) {
                        it.copy(status = VoicePackStatus.NOT_INSTALLED, progressPercent = 0)
                    }
                    false
                }
            }
        }
    }

    fun pickBestInstalledPack(
        preferredId: String,
        persona: AssistantPersona,
        preferBangla: Boolean = false
    ): NeuralVoicePack? {
        return PiperAudioUtils.selectBestPackForLanguage(
            catalog = catalog,
            preferredId = preferredId,
            persona = persona,
            preferBangla = preferBangla,
            isInstalled = ::isPackInstalled
        )
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

    private fun findLexiconFile(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isFile && it.name == "lexicon.txt" }

    private fun findEspeakDataDir(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }

    private fun findDictDir(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "dict" }

    suspend fun downloadAndInstallPack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val pack = catalog.firstOrNull { it.id == packId }
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown voice pack: $packId"))

        if (pack.isBundledAsset && !pack.bundledAssetPath.isNullOrBlank()) {
            val bundledOk = ensureBundledStarterInstalled()
            if (bundledOk) return@withContext Result.success(Unit)
        }

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

            FileInputStream(tempArchive).use { fis ->
                extractTarBz2StreamSafely(fis, targetDir)
            }

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

    private fun extractTarBz2StreamSafely(rawStream: InputStream, destDir: File) {
        val canonicalDest = destDir.canonicalPath + File.separator
        BufferedInputStream(rawStream).use { buffered ->
            BZip2CompressorInputStream(buffered).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    @Suppress("DEPRECATION")
                    var entry = tar.nextTarEntry
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
                        @Suppress("DEPRECATION")
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }

    fun deletePack(packId: String) {
        val pack = catalog.firstOrNull { it.id == packId } ?: return
        if (activePackId == packId) {
            runCatching { loadedTts?.release() }
            loadedTts = null
            activePackId = null
        }
        packDir(pack).deleteRecursively()
        refreshInstalledStates()
    }

    suspend fun importCustomOnnxFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pack = catalog.first { it.id == CUSTOM_AETHER_VOICE_ID }
            val name = VoiceSampleAnalyzer.resolveDisplayName(appContext, uri) ?: "custom_voice"
            val lowerName = name.lowercase()
            val targetDir = File(voicesRootDir, pack.id)
            targetDir.mkdirs()

            if (lowerName.endsWith(".tar.bz2") || lowerName.endsWith(".tbz2")) {
                targetDir.deleteRecursively()
                targetDir.mkdirs()
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    extractTarBz2StreamSafely(input, targetDir)
                } ?: throw IllegalStateException("Cannot read selected voice archive.")
                if (!isPackInstalled(pack)) {
                    targetDir.deleteRecursively()
                    throw IllegalStateException("Archive must contain a Piper .onnx model and tokens.txt")
                }
            } else if (lowerName.endsWith(".onnx")) {
                val outOnnx = File(targetDir, "model.onnx")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outOnnx).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read .onnx model file.")
                // Copy tokens.txt from any installed pack as fallback vocabulary if user didn't bundle one yet
                if (findTokensFile(targetDir) == null) {
                    val fallbackTokens = catalog
                        .sortedByDescending { it.id == BUNDLED_STARTER_VOICE_ID }
                        .firstNotNullOfOrNull { candidate -> findTokensFile(packDir(candidate)) }
                    if (fallbackTokens != null) {
                        fallbackTokens.copyTo(File(targetDir, "tokens.txt"), overwrite = true)
                    } else {
                        throw IllegalStateException("Please download a Piper voice pack or import paired tokens.txt first.")
                    }
                }
            } else if (lowerName == "tokens.txt") {
                val outTokens = File(targetDir, "tokens.txt")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outTokens).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read tokens.txt")
            } else {
                throw IllegalStateException("Select a Piper .tar.bz2 voice pack or .onnx model file.")
            }

            if (activePackId == pack.id) {
                runCatching { loadedTts?.release() }
                loadedTts = null
                activePackId = null
            }
            refreshInstalledStates()
            if (isPackInstalled(pack)) {
                "Custom Neural Model Ready ($name)"
            } else {
                "Imported $name — now select the paired tokens.txt or .onnx file"
            }
        }
    }

    suspend fun speakWithPack(
        text: String,
        pack: NeuralVoicePack,
        speed: Float,
        pitchFactor: Float = 1.0f,
        audioAttributes: AudioAttributes,
        onAmplitude: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (nativeBinaryUnavailable) return@withContext false

        if (!isPackInstalled(pack) && pack.isBundledAsset) {
            ensureBundledStarterInstalled()
        }
        val dir = packDir(pack)
        val modelFile = findOnnxFile(dir) ?: return@withContext false
        val tokensFile = findTokensFile(dir) ?: return@withContext false
        val lexiconFile = findLexiconFile(dir)
        val espeakDir = findEspeakDataDir(dir)
        val dictDir = findDictDir(dir)

        stopRequested = false

        try {
            val engine = ttsMutex.withLock {
                if (activePackId != pack.id || loadedTts == null) {
                    runCatching { loadedTts?.release() }
                    loadedTts = null
                    activePackId = null

                    val vitsConfig = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        lexicon = lexiconFile?.absolutePath.orEmpty(),
                        tokens = tokensFile.absolutePath,
                        dataDir = espeakDir?.absolutePath.orEmpty(),
                        dictDir = dictDir?.absolutePath.orEmpty()
                    )
                    val adaptiveThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    val modelConfig = OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = adaptiveThreads,
                        debug = false,
                        provider = "cpu"
                    )
                    val config = OfflineTtsConfig(
                        model = modelConfig,
                        ruleFsts = "",
                        ruleFars = "",
                        maxNumSentences = 1,
                        silenceScale = 0.16f
                    )
                    loadedTts = OfflineTts(assetManager = null, config = config)
                    activePackId = pack.id
                    _engineStatusLabel.value = "Piper Fast ($adaptiveThreads-Thread) · ${pack.displayName.substringBefore('·').trim()}"
                }
                loadedTts
            } ?: return@withContext false

            if (stopRequested) return@withContext false

            val sentences = PiperAudioUtils.splitSentencesForPipeline(text)
            if (sentences.isEmpty()) return@withContext false

            val effectiveSpeed = speed.coerceIn(0.75f, 1.45f)
            val effectivePitch = pitchFactor.coerceIn(0.78f, 1.25f)

            // Generate sentence 1 immediately for ultra-low latency first-audio, then overlap remaining sentences
            val firstGen = ttsMutex.withLock {
                engine.generate(
                    text = sentences.first(),
                    sid = pack.defaultSpeakerId,
                    speed = effectiveSpeed
                )
            }
            if (stopRequested || firstGen.samples.isEmpty()) {
                onAmplitude(0f)
                return@withContext false
            }

            val sampleRate = firstGen.sampleRate.coerceIn(8000, 48000)
            val audioChannel = Channel<FloatArray>(capacity = 2)

            coroutineScope {
                val producer = launch(Dispatchers.IO) {
                    try {
                        audioChannel.send(firstGen.samples)
                        for (i in 1 until sentences.size) {
                            if (stopRequested) break
                            val nextGen = ttsMutex.withLock {
                                if (stopRequested) null else engine.generate(
                                    text = sentences[i],
                                    sid = pack.defaultSpeakerId,
                                    speed = effectiveSpeed
                                )
                            } ?: break
                            if (!stopRequested && nextGen.samples.isNotEmpty()) {
                                audioChannel.send(nextGen.samples)
                            }
                        }
                    } finally {
                        audioChannel.close()
                    }
                }

                streamPcmChannel(
                    sampleRate = sampleRate,
                    pitchFactor = effectivePitch,
                    audioAttributes = audioAttributes,
                    channel = audioChannel,
                    onAmplitude = onAmplitude
                )
                producer.join()
            }
            true
        } catch (ule: UnsatisfiedLinkError) {
            nativeBinaryUnavailable = true
            _engineStatusLabel.value = "System Fallback (ABI unavailable)"
            Log.w(TAG, "Sherpa-ONNX native library unavailable on this ABI; falling back to System TTS", ule)
            onAmplitude(0f)
            false
        } catch (ce: CancellationException) {
            stopPlayback()
            onAmplitude(0f)
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Piper neural synthesis failed for '${pack.id}', falling back to System TTS", t)
            onAmplitude(0f)
            false
        }
    }

    private suspend fun streamPcmChannel(
        sampleRate: Int,
        pitchFactor: Float,
        audioAttributes: AudioAttributes,
        channel: Channel<FloatArray>,
        onAmplitude: (Float) -> Unit
    ) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && kotlin.math.abs(pitchFactor - 1.0f) > 0.02f) {
            runCatching {
                track.playbackParams = PlaybackParams().apply {
                    pitch = pitchFactor.coerceIn(0.78f, 1.25f)
                    speed = 1.0f
                }
            }
        }

        activeTrack = track
        val chunkSamples = (sampleRate / 25).coerceIn(512, 2048)
        val pcmBytes = ByteArray(chunkSamples * 2)

        try {
            track.play()
            for (samples in channel) {
                if (stopRequested) break
                var offset = 0
                while (offset < samples.size && !stopRequested) {
                    val count = min(chunkSamples, samples.size - offset)
                    val amp = PiperAudioUtils.floatFrameToPcm16(
                        samples = samples,
                        offset = offset,
                        length = count,
                        outBytes = pcmBytes
                    )
                    onAmplitude(amp)
                    val written = track.write(pcmBytes, 0, count * 2)
                    if (written < 0) break
                    offset += count
                }
            }
            if (!stopRequested) {
                val remainingMs = ((minBuf.toFloat() / (sampleRate * 2)) * 1000f).toLong().coerceIn(80L, 240L)
                kotlinx.coroutines.delay(remainingMs)
            }
        } finally {
            onAmplitude(0f)
            if (activeTrack === track) {
                activeTrack = null
            }
            runCatching {
                track.pause()
                track.flush()
                track.stop()
            }
            runCatching { track.release() }
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
        runCatching { loadedTts?.release() }
        loadedTts = null
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
