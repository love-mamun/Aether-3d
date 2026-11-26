package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ai.AssistantPersona
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "LocalWakeWordEngine"

data class KwsModelFiles(
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String,
    val tokensPath: String,
    val keywordsPath: String
)

enum class KwsPackInstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

data class KwsEngineUiState(
    val status: KwsPackInstallStatus = KwsPackInstallStatus.NOT_INSTALLED,
    val progressPercent: Int = 0,
    val statusLabel: String = "Zero-Beep Silence Gate",
    val errorMessage: String? = null
)

/**
 * Dual-mode local passive wake engine designed to avoid continuous Google SpeechRecognizer
 * 350ms polling/beeping loops:
 * - When the 3.3 MB Sherpa-ONNX English KWS pack is installed, runs offline KeywordSpotter
 *   directly on AudioRecord(VOICE_RECOGNITION) PCM frames.
 * - Otherwise runs a lightweight RMS silence gate on AudioRecord and invokes a single
 *   SpeechRecognizer burst ONLY when human voice energy crosses threshold (then waits quietly).
 */
class LocalWakeWordEngine(
    context: Context,
    private val commRouter: CommunicationAudioRouter
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val kwsRootDir = File(appContext.filesDir, "kws_models/sherpa-kws-en-3.3M")

    private val _uiState = MutableStateFlow(computeInitialState())
    val uiState: StateFlow<KwsEngineUiState> = _uiState.asStateFlow()

    @Volatile
    private var monitorJob: Job? = null

    @Volatile
    private var spotter: KeywordSpotter? = null

    @Volatile
    private var downloadJob: Job? = null

    private fun computeInitialState(): KwsEngineUiState {
        return if (findInstalledModelFiles() != null) {
            KwsEngineUiState(
                status = KwsPackInstallStatus.READY,
                progressPercent = 100,
                statusLabel = "On-Device Sherpa KWS Ready (3.3M)"
            )
        } else {
            KwsEngineUiState(
                status = KwsPackInstallStatus.NOT_INSTALLED,
                progressPercent = 0,
                statusLabel = "Silence-Gated Monitor (Download 3.3 MB KWS for 100% offline wake)"
            )
        }
    }

    fun isKwsPackInstalled(): Boolean = findInstalledModelFiles() != null

    fun findInstalledModelFiles(): KwsModelFiles? {
        if (!kwsRootDir.exists()) return null
        val allFiles = kwsRootDir.walkTopDown().filter { it.isFile }.toList()
        val encoder = allFiles.firstOrNull {
            it.name.contains("encoder", ignoreCase = true) &&
                it.name.contains("int8", ignoreCase = true) &&
                it.name.endsWith(".onnx", ignoreCase = true)
        } ?: allFiles.firstOrNull {
            it.name.contains("encoder", ignoreCase = true) &&
                it.name.endsWith(".onnx", ignoreCase = true)
        } ?: return null

        val decoder = allFiles.firstOrNull {
            it.name.contains("decoder", ignoreCase = true) &&
                it.name.contains("int8", ignoreCase = true) &&
                it.name.endsWith(".onnx", ignoreCase = true)
        } ?: allFiles.firstOrNull {
            it.name.contains("decoder", ignoreCase = true) &&
                it.name.endsWith(".onnx", ignoreCase = true)
        } ?: return null

        val joiner = allFiles.firstOrNull {
            it.name.contains("joiner", ignoreCase = true) &&
                it.name.contains("int8", ignoreCase = true) &&
                it.name.endsWith(".onnx", ignoreCase = true)
        } ?: allFiles.firstOrNull {
            it.name.contains("joiner", ignoreCase = true) &&
                it.name.endsWith(".onnx", ignoreCase = true)
        } ?: return null

        val tokens = allFiles.firstOrNull { it.name.equals("tokens.txt", ignoreCase = true) }
            ?: return null
        val keywords = allFiles.firstOrNull { it.name.equals("keywords.txt", ignoreCase = true) }
            ?: return null

        return KwsModelFiles(
            encoderPath = encoder.absolutePath,
            decoderPath = decoder.absolutePath,
            joinerPath = joiner.absolutePath,
            tokensPath = tokens.absolutePath,
            keywordsPath = keywords.absolutePath
        )
    }

    fun downloadAndInstallKwsPack() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _uiState.value = KwsEngineUiState(
                status = KwsPackInstallStatus.DOWNLOADING,
                progressPercent = 2,
                statusLabel = "Downloading Sherpa KWS (3.3 MB)..."
            )
            val tmpArchive = File(appContext.cacheDir, "sherpa_kws_pack.tar.bz2")
            try {
                val request = Request.Builder().url(KWS_MODEL_DOWNLOAD_URL).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty download body")
                    val totalBytes = body.contentLength().coerceAtLeast(3_300_000L)
                    var readBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tmpArchive).use { output ->
                            val buf = ByteArray(16_384)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                readBytes += read
                                val pct = ((readBytes.toDouble() / totalBytes) * 80.0).toInt().coerceIn(2, 80)
                                _uiState.value = KwsEngineUiState(
                                    status = KwsPackInstallStatus.DOWNLOADING,
                                    progressPercent = pct,
                                    statusLabel = "Downloading Sherpa KWS ($pct%)..."
                                )
                            }
                        }
                    }
                }

                _uiState.value = KwsEngineUiState(
                    status = KwsPackInstallStatus.EXTRACTING,
                    progressPercent = 88,
                    statusLabel = "Extracting KWS ONNX files..."
                )
                if (kwsRootDir.exists()) {
                    kwsRootDir.deleteRecursively()
                }
                kwsRootDir.mkdirs()

                tmpArchive.inputStream().use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        BZip2CompressorInputStream(bis).use { bz2 ->
                            TarArchiveInputStream(bz2).use { tar ->
                                var entry = tar.nextTarEntry
                                val buf = ByteArray(16_384)
                                while (entry != null) {
                                    if (!entry.isDirectory) {
                                        val cleanName = File(entry.name).name
                                        if (cleanName.endsWith(".onnx") || cleanName.endsWith(".txt")) {
                                            val outFile = File(kwsRootDir, cleanName)
                                            FileOutputStream(outFile).use { fos ->
                                                while (true) {
                                                    val n = tar.read(buf)
                                                    if (n < 0) break
                                                    fos.write(buf, 0, n)
                                                }
                                            }
                                        }
                                    }
                                    entry = tar.nextTarEntry
                                }
                            }
                        }
                    }
                }

                val verified = findInstalledModelFiles()
                    ?: throw IllegalStateException("Extracted pack missing ONNX or tokens files")
                _uiState.value = KwsEngineUiState(
                    status = KwsPackInstallStatus.READY,
                    progressPercent = 100,
                    statusLabel = "On-Device Sherpa KWS Ready (${File(verified.encoderPath).name})"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KWS pack install failed", e)
                _uiState.value = KwsEngineUiState(
                    status = KwsPackInstallStatus.ERROR,
                    progressPercent = 0,
                    statusLabel = "KWS install failed",
                    errorMessage = e.localizedMessage ?: "Download error"
                )
            } finally {
                runCatching { tmpArchive.delete() }
            }
        }
    }

    /**
     * Generates a safe runtime keywords file keeping valid archive entries and optionally
     * adding custom wake-phrase BPE tokenization ONLY if every token exists in tokens.txt.
     */
    private fun buildRuntimeKeywordsFile(
        files: KwsModelFiles,
        customWakePhrase: String,
        persona: AssistantPersona
    ): String {
        val baseFile = File(files.keywordsPath)
        val tokensSet = runCatching {
            File(files.tokensPath).readLines()
                .mapNotNull { line -> line.substringBefore(' ').trim().takeIf { it.isNotBlank() } }
                .toHashSet()
        }.getOrDefault(emptySet())

        val baseLines = runCatching {
            baseFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        val candidatePhrases = listOf(
            customWakePhrase.trim(),
            "HEY ${persona.displayName.uppercase(Locale.US)}",
            "HEY AETHER",
            "AETHER"
        ).distinct().filter { it.isNotBlank() }

        val customLines = candidatePhrases.mapNotNull { phrase ->
            val tokenized = tokenizeForBpeTokens(phrase, tokensSet)
            if (tokenized != null) "$tokenized @${phrase.lowercase(Locale.US)}" else null
        }

        val combined = (customLines + baseLines).distinct()
        if (combined.isEmpty()) return files.keywordsPath

        val runtimeFile = File(kwsRootDir, "runtime_keywords.txt")
        runtimeFile.writeText(combined.joinToString("\n") + "\n")
        return runtimeFile.absolutePath
    }

    private fun tokenizeForBpeTokens(phrase: String, tokensSet: Set<String>): String? {
        if (tokensSet.isEmpty()) return null
        val words = phrase.uppercase(Locale.US)
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^A-Z]"), "") }
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val outTokens = mutableListOf<String>()
        for (word in words) {
            // SentencePiece BPE uses U+2581 ('▁') at word boundary
            var remaining = "▁$word"
            var guard = 0
            while (remaining.isNotEmpty() && guard < 24) {
                guard++
                var matched: String? = null
                for (len in remaining.length downTo 1) {
                    val prefix = remaining.substring(0, len)
                    if (prefix in tokensSet) {
                        matched = prefix
                        break
                    }
                }
                if (matched == null) return null
                outTokens.add(matched)
                remaining = remaining.substring(matched.length)
            }
        }
        return if (outTokens.isEmpty()) null else outTokens.joinToString(" ")
    }

    @SuppressLint("MissingPermission")
    fun startPassiveMonitor(
        customWakePhrase: String,
        persona: AssistantPersona,
        threshold: Float,
        pauseDuringMusic: Boolean,
        shouldSuspendProvider: () -> Boolean,
        onOfflineKeywordHit: (WakeWordMatch) -> Unit,
        onVoiceEnergySpeechGate: () -> Unit
    ) {
        val micGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) return

        val previousJob = monitorJob
        monitorJob = scope.launch {
            runCatching { previousJob?.cancel() }
            runCatching { previousJob?.join() }
            val installed = findInstalledModelFiles()
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            var activeSpotter: KeywordSpotter? = null
            if (installed != null) {
                activeSpotter = runCatching {
                    val modelFilesValid = File(installed.encoderPath).length() > 1024L &&
                        File(installed.decoderPath).length() > 1024L &&
                        File(installed.joinerPath).length() > 1024L &&
                        File(installed.tokensPath).length() > 0L
                    if (!modelFilesValid) {
                        throw IllegalStateException("Sherpa KWS model files incomplete")
                    }
                    val runtimeKeywordsPath = buildRuntimeKeywordsFile(installed, customWakePhrase, persona)
                    val hasKeywords = runCatching {
                        File(runtimeKeywordsPath).readLines().any { it.isNotBlank() }
                    }.getOrDefault(false)
                    if (!hasKeywords) {
                        throw IllegalStateException("Runtime keywords file empty")
                    }
                    val cfg = KeywordSpotterConfig().apply {
                        featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80, dither = 0f)
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = installed.encoderPath,
                                decoder = installed.decoderPath,
                                joiner = installed.joinerPath
                            ),
                            tokens = installed.tokensPath,
                            numThreads = 1,
                            debug = false,
                            provider = "cpu"
                        )
                        maxActivePaths = 4
                        keywordsFile = runtimeKeywordsPath
                        keywordsScore = 1.0f
                        keywordsThreshold = threshold.coerceIn(0.10f, 0.85f)
                        numTrailingBlanks = 1
                    }
                    KeywordSpotter(null, cfg)
                }.onFailure { err ->
                    Log.w(TAG, "Sherpa KeywordSpotter init failed; falling back to silence gate", err)
                }.getOrNull()
                spotter = activeSpotter
            }

            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "Passive AudioRecord failed to initialize")
                    return@launch
                }
                audioRecord.startRecording()

                val shortBuf = ShortArray(1280) // 80ms frames at 16kHz
                val floatBuf = FloatArray(1280)
                val stream = activeSpotter?.createStream("")
                var loudStreak = 0
                var lastGateTriggerMs = 0L

                while (isActive) {
                    if (shouldSuspendProvider() || (pauseDuringMusic && commRouter.isMusicPlaybackActive())) {
                        loudStreak = 0
                        delay(450L)
                        continue
                    }

                    val read = audioRecord.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) {
                        delay(40L)
                        continue
                    }

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = shortBuf[i].toDouble()
                        sumSq += s * s
                        floatBuf[i] = (shortBuf[i] / 32768.0f).coerceIn(-1f, 1f)
                    }
                    val rms = sqrt(sumSq / read.coerceAtLeast(1))

                    if (activeSpotter != null && stream != null) {
                        stream.acceptWaveform(floatBuf.copyOf(read), sampleRate)
                        while (activeSpotter.isReady(stream)) {
                            activeSpotter.decode(stream)
                        }
                        val kwResult = activeSpotter.getResult(stream)
                        val spotted = kwResult.keyword.trim()
                        if (spotted.isNotBlank()) {
                            Log.d(TAG, "On-device Sherpa KWS spotted: '$spotted'")
                            activeSpotter.reset(stream)
                            withContext(Dispatchers.Main) {
                                onOfflineKeywordHit(
                                    WakeWordMatch(
                                        matchedPhrase = spotted,
                                        trailingCommand = ""
                                    )
                                )
                            }
                            return@launch
                        }
                    } else {
                        // Zero-beep RMS silence gate: only wake SpeechRecognizer burst on real voice energy
                        if (rms >= 950.0) {
                            loudStreak++
                            val now = SystemClock.elapsedRealtime()
                            if (loudStreak >= 2 && now - lastGateTriggerMs > 2_500L) {
                                lastGateTriggerMs = now
                                loudStreak = 0
                                withContext(Dispatchers.Main) {
                                    onVoiceEnergySpeechGate()
                                }
                                return@launch
                            }
                        } else {
                            loudStreak = 0
                        }
                    }
                }
                stream?.release()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Passive KWS loop stopped: ${e.message}")
            } finally {
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                runCatching { activeSpotter?.release() }
                spotter = null
            }
        }
    }

    fun stopPassiveMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    companion object {
        const val KWS_MODEL_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    }
}
