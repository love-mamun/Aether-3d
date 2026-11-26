package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun voiceIsolationDsp_attenuatesSubBassAndSegmentsVoice() {
    val caps = com.example.audio.AudioEffectCapabilities(
      aecState = com.example.audio.AudioEffectState.UNSUPPORTED,
      nsState = com.example.audio.AudioEffectState.UNSUPPORTED,
      agcState = com.example.audio.AudioEffectState.UNSUPPORTED
    )
    assertTrue(caps.softwareNoiseSuppressionBoostNeeded)
    assertTrue(caps.softwareAgcBoostNeeded)
    assertTrue(caps.softwareEchoSuppressionActive)

    val dsp = com.example.audio.VoiceIsolationDsp(
      sampleRateHz = 16_000,
      frameSamples = 320,
      hardwareCapabilities = caps
    )

    val subBassFrame = ShortArray(320) { i ->
      (6000.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 60.0 * i / 16_000.0)).toInt().toShort()
    }
    var lastRumbleRes: com.example.audio.DspFrameResult? = null
    repeat(15) {
      val res = dsp.processFrame(subBassFrame)
      lastRumbleRes = res
      assertNull(dsp.feedForUtterance(res))
    }
    assertTrue((lastRumbleRes?.filteredRms ?: 9999f) < 1200f)
    assertFalse(lastRumbleRes?.isSpeechFrame == true)

    val voiceFrame = ShortArray(320) { i ->
      (4500.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 650.0 * i / 16_000.0)).toInt().toShort()
    }
    val silenceFrame = ShortArray(320)
    var speechFrames = 0
    repeat(35) {
      val res = dsp.processFrame(voiceFrame)
      if (res.isSpeechFrame) speechFrames++
      dsp.feedForUtterance(res)
    }
    assertTrue(speechFrames >= 10)

    var emitted: com.example.audio.CompletedUtterancePcm? = null
    repeat(50) {
      val seg = dsp.feedForUtterance(dsp.processFrame(silenceFrame))
      if (seg != null) emitted = seg
    }
    assertNotNull(emitted)
    assertTrue((emitted?.durationMs ?: 0L) >= 450L)
  }

  @Test
  fun audioWavEncoder_writesValidRiffWaveHeader() {
    val samples = shortArrayOf(120, -240, 360, -480)
    val wav = com.example.audio.AudioWavEncoder.encodePcm16MonoWav(samples, sampleRateHz = 16_000)
    assertEquals(44 + samples.size * 2, wav.size)
    assertEquals('R'.code.toByte(), wav[0])
    assertEquals('I'.code.toByte(), wav[1])
    assertEquals('F'.code.toByte(), wav[2])
    assertEquals('F'.code.toByte(), wav[3])
    assertEquals('W'.code.toByte(), wav[8])
    assertEquals('A'.code.toByte(), wav[9])
    assertEquals('V'.code.toByte(), wav[10])
    assertEquals('E'.code.toByte(), wav[11])
  }

  @Test
  fun assistantPreferences_defaultsPreferNvidiaPrimaryTrue() {
    val prefs = com.example.data.local.AssistantPreferences()
    assertTrue(prefs.preferNvidiaPrimary)
  }

  @Test
  fun geminiCommandService_routesNvidiaPrimaryThenFallsBackToGemini() = kotlinx.coroutines.test.runTest {
    var geminiCalled = 0
    val primarySuccess = com.example.ai.GeminiCommandService.routePrimaryAndFallback(
      audioWavBytes = null,
      preferNvidiaPrimary = true,
      nvidiaConfigured = true,
      tryNvidia = {
        Result.success(
          com.example.ai.CommandPlan(
            reply = "From NVIDIA",
            needsScreenObservation = false,
            continueLoopAfterAction = false,
            steps = emptyList()
          )
        )
      },
      tryGemini = {
        geminiCalled++
        Result.success(
          com.example.ai.CommandPlan(
            reply = "From Gemini",
            needsScreenObservation = false,
            continueLoopAfterAction = false,
            steps = emptyList()
          )
        )
      }
    )
    assertTrue(primarySuccess.isSuccess)
    assertEquals("From NVIDIA", primarySuccess.getOrNull()?.reply)
    assertEquals(0, geminiCalled)

    val fallbackSuccess = com.example.ai.GeminiCommandService.routePrimaryAndFallback(
      audioWavBytes = null,
      preferNvidiaPrimary = true,
      nvidiaConfigured = true,
      tryNvidia = { Result.failure(IllegalStateException("NVIDIA rate limited")) },
      tryGemini = {
        geminiCalled++
        Result.success(
          com.example.ai.CommandPlan(
            reply = "Gemini Fallback OK",
            needsScreenObservation = false,
            continueLoopAfterAction = false,
            steps = emptyList()
          )
        )
      }
    )
    assertTrue(fallbackSuccess.isSuccess)
    assertEquals("Gemini Fallback OK", fallbackSuccess.getOrNull()?.reply)
    assertEquals(1, geminiCalled)

    val bothFailed = com.example.ai.GeminiCommandService.routePrimaryAndFallback(
      audioWavBytes = null,
      preferNvidiaPrimary = true,
      nvidiaConfigured = true,
      tryNvidia = { Result.failure(IllegalStateException("NVIDIA timeout")) },
      tryGemini = { Result.failure(IllegalStateException("Gemini unavailable")) }
    )
    assertTrue(bothFailed.isFailure)
    val errMsg = bothFailed.exceptionOrNull()?.message.orEmpty()
    assertTrue(errMsg.contains("NVIDIA primary"))
    assertTrue(errMsg.contains("Gemini fallback"))
  }

  @Test
  fun speechErrorPolicy_classifiesServiceFailuresForFilteredMicFallback() {
    val clientErr = com.example.system.SpeechErrorPolicy.classifyError(android.speech.SpeechRecognizer.ERROR_CLIENT)
    assertTrue(clientErr.isServiceOrDeviceFailure)
    assertFalse(clientErr.shouldRetryBusy)

    val serverDisconnectedErr = com.example.system.SpeechErrorPolicy.classifyError(11)
    assertTrue(serverDisconnectedErr.isServiceOrDeviceFailure)

    val busyErr = com.example.system.SpeechErrorPolicy.classifyError(android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    assertTrue(busyErr.isServiceOrDeviceFailure)
    assertTrue(busyErr.shouldRetryBusy)

    val noMatchErr = com.example.system.SpeechErrorPolicy.classifyError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
    assertFalse(noMatchErr.isServiceOrDeviceFailure)

    val permErr = com.example.system.SpeechErrorPolicy.classifyError(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
    assertFalse(permErr.isServiceOrDeviceFailure)
  }

  @Test
  fun voiceIsolationDsp_capturesModerateQuietHumanSpeech() {
    val dsp = com.example.audio.VoiceIsolationDsp(
      sampleRateHz = 16_000,
      frameSamples = 320
    )
    // Quiet-to-moderate 500 Hz speech-band tone (~500 peak -> ~353 RMS)
    val softVoiceFrame = ShortArray(320) { i ->
      (500.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 500.0 * i / 16_000.0)).toInt().toShort()
    }
    var speechFrames = 0
    repeat(30) {
      val res = dsp.processFrame(softVoiceFrame)
      if (res.isSpeechFrame) speechFrames++
      dsp.feedForUtterance(res)
    }
    assertTrue("Expected soft human voice to trigger speech detection", speechFrames >= 10)
  }

  @Test
  fun piperAudioUtils_convertsFloatSamplesToPcm16AndRms() {
    val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
    val out = ByteArray(samples.size * 2)
    val amp = com.example.voice.PiperAudioUtils.floatFrameToPcm16(
      samples = samples,
      offset = 0,
      length = samples.size,
      outBytes = out
    )
    assertTrue(amp in 0.1f..1.0f)
    // Sample[1] = 0.5f -> ~16383 (0xFF, 0x3F in little-endian)
    val s1 = (out[2].toInt() and 0xFF) or (out[3].toInt() shl 8)
    assertTrue(s1 in 16380..16385)
    // Sample[4] = -1.0f -> -32767
    val s4 = ((out[8].toInt() and 0xFF) or (out[9].toInt() shl 8)).toShort().toInt()
    assertTrue(s4 <= -32766)
  }

  @Test
  fun piperAudioUtils_selectsBundledEnglishAndBanglaPacksByLanguage() {
    val enStarter = com.example.voice.NeuralVoicePack(
      id = com.example.voice.BUNDLED_STARTER_VOICE_ID,
      displayName = "Amy",
      languageTag = "en-US",
      genderHint = "Female",
      downloadSizeMb = 20.1f,
      downloadUrl = "https://example.com/en.tar.bz2",
      extractedFolderName = "amy",
      isBundledAsset = true
    )
    val bnPack = com.example.voice.NeuralVoicePack(
      id = "bn_BD-mimic3-multi_low",
      displayName = "Bangla",
      languageTag = "bn-BD",
      genderHint = "Neutral",
      downloadSizeMb = 76.2f,
      downloadUrl = "https://example.com/bn.tar.bz2",
      extractedFolderName = "bn"
    )
    val catalog = listOf(enStarter, bnPack)

    val enPicked = com.example.voice.PiperAudioUtils.selectBestPackForLanguage(
      catalog = catalog,
      preferredId = "non_installed_id",
      persona = com.example.ai.AssistantPersona.AETHER,
      preferBangla = false,
      isInstalled = { it.id == com.example.voice.BUNDLED_STARTER_VOICE_ID }
    )
    assertEquals(com.example.voice.BUNDLED_STARTER_VOICE_ID, enPicked?.id)

    val bnPickedWhenMissing = com.example.voice.PiperAudioUtils.selectBestPackForLanguage(
      catalog = catalog,
      preferredId = "bn_BD-mimic3-multi_low",
      persona = com.example.ai.AssistantPersona.AETHER,
      preferBangla = true,
      isInstalled = { it.id == com.example.voice.BUNDLED_STARTER_VOICE_ID }
    )
    assertNull(bnPickedWhenMissing)

    val bnPickedWhenInstalled = com.example.voice.PiperAudioUtils.selectBestPackForLanguage(
      catalog = catalog,
      preferredId = "bn_BD-mimic3-multi_low",
      persona = com.example.ai.AssistantPersona.ARIA,
      preferBangla = true,
      isInstalled = { true }
    )
    assertEquals("bn_BD-mimic3-multi_low", bnPickedWhenInstalled?.id)
  }

  @Test
  fun messagingUiHeuristics_parsesWhatsAppAndBanglishTargets() {
    val piped = com.example.accessibility.MessagingUiHeuristics.parseAppAndRecipient(
      target = "WhatsApp|Ma",
      explicitRecipient = ""
    )
    assertEquals("WhatsApp", piped.appName)
    assertEquals("Ma", piped.recipientName)

    val spaceCombined = com.example.accessibility.MessagingUiHeuristics.parseAppAndRecipient(
      target = "WhatsApp Ma",
      explicitRecipient = ""
    )
    assertEquals("WhatsApp", spaceCombined.appName)
    assertEquals("Ma", spaceCombined.recipientName)

    val explicit = com.example.accessibility.MessagingUiHeuristics.parseAppAndRecipient(
      target = "Telegram",
      explicitRecipient = "Ma"
    )
    assertEquals("Telegram", explicit.appName)
    assertEquals("Ma", explicit.recipientName)
  }

  @Test
  fun messagingUiHeuristics_skipsMetaAiAndSelectsBottomComposerAndSendButton() {
    val nodes = listOf(
      com.example.accessibility.UiNodeCandidate(
        index = 0,
        text = "Meta AI",
        contentDesc = "Meta AI",
        hint = "",
        viewIdSuffix = "meta_ai_btn",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        cxNorm = 880,
        cyNorm = 780
      ),
      com.example.accessibility.UiNodeCandidate(
        index = 1,
        text = "Search",
        contentDesc = "Search",
        hint = "Search…",
        viewIdSuffix = "menuitem_search",
        isClickable = true,
        isEditable = true,
        isScrollable = false,
        cxNorm = 500,
        cyNorm = 110
      ),
      com.example.accessibility.UiNodeCandidate(
        index = 2,
        text = "Ma",
        contentDesc = "",
        hint = "",
        viewIdSuffix = "conversations_row_contact_name",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        cxNorm = 340,
        cyNorm = 290
      ),
      com.example.accessibility.UiNodeCandidate(
        index = 3,
        text = "",
        contentDesc = "",
        hint = "Message",
        viewIdSuffix = "entry",
        isClickable = true,
        isEditable = true,
        isScrollable = false,
        cxNorm = 450,
        cyNorm = 910
      ),
      com.example.accessibility.UiNodeCandidate(
        index = 4,
        text = "",
        contentDesc = "Voice message",
        hint = "",
        viewIdSuffix = "voice_note_btn",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        cxNorm = 920,
        cyNorm = 910
      ),
      com.example.accessibility.UiNodeCandidate(
        index = 5,
        text = "",
        contentDesc = "Send",
        hint = "",
        viewIdSuffix = "send",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        cxNorm = 920,
        cyNorm = 910
      )
    )

    val searchTrigger = com.example.accessibility.MessagingUiHeuristics.pickSearchTrigger(nodes)
    assertEquals(1, searchTrigger?.index)

    val contactRow = com.example.accessibility.MessagingUiHeuristics.pickContactRow(nodes, "Ma")
    assertEquals(2, contactRow?.index)

    val composer = com.example.accessibility.MessagingUiHeuristics.pickBottomComposer(nodes)
    assertEquals(3, composer?.index)

    val sendBtn = com.example.accessibility.MessagingUiHeuristics.pickSendButton(nodes)
    assertEquals(5, sendBtn?.index)
  }

  @Test
  fun screenLoopGuard_detectsStallsAndOcrContactMatches() {
    val sig = "com.whatsapp#Chats@5|Ma@12|Baba@18"
    val (s1, stop1) = com.example.core.ScreenLoopGuard.evaluateStall(sig, sig, 0)
    assertEquals(1, s1)
    assertFalse(stop1)

    val (s2, stop2) = com.example.core.ScreenLoopGuard.evaluateStall(sig, sig, s1)
    assertEquals(2, s2)
    assertFalse(stop2)

    val (s3, stop3) = com.example.core.ScreenLoopGuard.evaluateStall(sig, sig, s2)
    assertEquals(3, s3)
    assertFalse(stop3)

    val (s4, stop4) = com.example.core.ScreenLoopGuard.evaluateStall(sig, sig, s3)
    assertEquals(4, s4)
    assertTrue(stop4)

    val (reset, stopAfterMove) = com.example.core.ScreenLoopGuard.evaluateStall(sig, "com.whatsapp#Other@12", s4)
    assertEquals(0, reset)
    assertFalse(stopAfterMove)

    val ocrHit = com.example.core.ScreenLoopGuard.findBestOcrContactMatch(
      lines = listOf(
        com.example.vision.OcrLineItem("Ma", 200, 90), // Top search box (excluded)
        com.example.vision.OcrLineItem("Ma", 300, 310) // Chat row (included)
      ),
      contactQuery = "Ma"
    )
    assertEquals(310, ocrHit?.centerYNorm)
  }

  @Test
  fun geminiCommandService_parsesSendAppMessageWithRecipient() {
    val service = com.example.ai.GeminiCommandService()
    val json = """
      {
        "reply": "অবশ্যই, আমি এখনই হোয়াটসঅ্যাপে মাকে মেসেজ পাঠাচ্ছি।",
        "needsScreenObservation": false,
        "continueLoopAfterAction": false,
        "steps": [
          {
            "stepId": "s0",
            "actionType": "SEND_APP_MESSAGE",
            "label": "Message Ma on WhatsApp",
            "target": "WhatsApp",
            "recipient": "Ma",
            "textPayload": "Ami aj basai jabo na",
            "isSensitive": false
          }
        ]
      }
    """.trimIndent()
    val plan = service.parseCommandPlan(json)
    assertEquals(1, plan.steps.size)
    val step = plan.steps.first()
    assertEquals(com.example.ai.ActionType.SEND_APP_MESSAGE, step.actionType)
    assertEquals("WhatsApp", step.target)
    assertEquals("Ma", step.recipient)
    assertEquals("Ami aj basai jabo na", step.textPayload)
  }

  @Test
  fun offlineCommandRouter_handlesFlashDndWifiCallsAndLaunchesWithoutBlockingCompoundPosts() {
    val flashOn = com.example.system.OfflineCommandRouter.tryMatch("Turn on flashlight")
    assertNotNull(flashOn)
    assertEquals(com.example.ai.ActionType.TOGGLE_TORCH, flashOn?.plan?.steps?.first()?.actionType)
    assertTrue(flashOn?.plan?.steps?.first()?.boolValue == true)

    val flashOff = com.example.system.OfflineCommandRouter.tryMatch("flash off koro")
    assertNotNull(flashOff)
    assertEquals(com.example.ai.ActionType.TOGGLE_TORCH, flashOff?.plan?.steps?.first()?.actionType)
    assertFalse(flashOff?.plan?.steps?.first()?.boolValue == true)

    val dndOn = com.example.system.OfflineCommandRouter.tryMatch("Enable DND")
    assertNotNull(dndOn)
    assertEquals(com.example.ai.ActionType.TOGGLE_DND, dndOn?.plan?.steps?.first()?.actionType)
    assertTrue(dndOn?.plan?.steps?.first()?.boolValue == true)

    val wifiOff = com.example.system.OfflineCommandRouter.tryMatch("wifi off")
    assertNotNull(wifiOff)
    assertEquals(com.example.ai.ActionType.TOGGLE_WIFI, wifiOff?.plan?.steps?.first()?.actionType)

    val callMa = com.example.system.OfflineCommandRouter.tryMatch("call Ma")
    assertNotNull(callMa)
    assertEquals(com.example.ai.ActionType.DIAL_CONTACT, callMa?.plan?.steps?.first()?.actionType)
    assertEquals("Ma", callMa?.plan?.steps?.first()?.target)

    val openDial = com.example.system.OfflineCommandRouter.tryMatch(
      rawPrompt = "open Dial",
      installedAppsResolver = { mapOf("Dial" to "com.google.android.dialer") }
    )
    assertNotNull(openDial)
    assertEquals(com.example.ai.ActionType.LAUNCH_APP, openDial?.plan?.steps?.first()?.actionType)
    assertEquals("Dial", openDial?.plan?.steps?.first()?.target)

    // Compound goals MUST bypass offline router so full AI + ScreenAutomationAgent executes them!
    val fbPost = com.example.system.OfflineCommandRouter.tryMatch("Facebook khule akta post koro I am happy today")
    assertNull(fbPost)

    val waMsg = com.example.system.OfflineCommandRouter.tryMatch("WhatsApp a ma ke message koro Ami aj basai jabo na")
    assertNull(waMsg)
  }

  @Test
  fun voiceSampleAnalyzer_calibratesPitchFromSyntheticRiffPcm16Wav() {
    // Generate 1.2s synthetic 120 Hz male-range voiced sine wave at 16 kHz PCM-16
    val sampleRate = 16_000
    val count = (sampleRate * 1.2).toInt()
    val samples = ShortArray(count) { i ->
      (12_000.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 120.0 * i / sampleRate.toDouble())).toInt().toShort()
    }
    val wavBytes = com.example.audio.AudioWavEncoder.encodePcm16MonoWav(samples, sampleRateHz = sampleRate)
    val analysis = com.example.voice.VoiceSampleAnalyzer.analyzeWavBytes(wavBytes)

    assertEquals(16_000, analysis.sampleRateHz)
    assertEquals(1, analysis.channels)
    assertTrue("Expected ~1.2s duration, got ${analysis.durationSeconds}", analysis.durationSeconds in 1.1f..1.3f)
    assertTrue("Expected pitch near 120 Hz, got ${analysis.estimatedPitchHz}", analysis.estimatedPitchHz in 105f..135f)
    assertTrue("Expected calibrated factor within 0.80..1.05, got ${analysis.calibratedPitchFactor}", analysis.calibratedPitchFactor in 0.80f..1.05f)
    assertTrue(analysis.summaryLabel.contains("Hz"))
  }

  @Test
  fun piperAudioUtils_splitsSentencesAndSeparatesAetherAndAriaVoices() {
    val chunks = com.example.voice.PiperAudioUtils.splitSentencesForPipeline(
      "Hello! Turning flashlight on now. আমি কি আর কিছু সাহায্য করবো?"
    )
    assertEquals(3, chunks.size)

    val aetherStarter = com.example.voice.NeuralVoicePack(
      id = "en_US-ryan-medium-int8",
      displayName = "Ryan",
      languageTag = "en-US",
      genderHint = "Male",
      downloadSizeMb = 63f,
      downloadUrl = "https://example.com/ryan.tar.bz2",
      extractedFolderName = "ryan"
    )
    val ariaStarter = com.example.voice.NeuralVoicePack(
      id = com.example.voice.BUNDLED_STARTER_VOICE_ID,
      displayName = "Amy",
      languageTag = "en-US",
      genderHint = "Female",
      downloadSizeMb = 20f,
      downloadUrl = "https://example.com/amy.tar.bz2",
      extractedFolderName = "amy",
      isBundledAsset = true
    )
    val catalog = listOf(ariaStarter, aetherStarter)

    val pickedForAether = com.example.voice.PiperAudioUtils.selectBestPackForLanguage(
      catalog = catalog,
      preferredId = "en_US-ryan-medium-int8",
      persona = com.example.ai.AssistantPersona.AETHER,
      preferBangla = false,
      isInstalled = { true }
    )
    assertEquals("en_US-ryan-medium-int8", pickedForAether?.id)

    val pickedForAria = com.example.voice.PiperAudioUtils.selectBestPackForLanguage(
      catalog = catalog,
      preferredId = com.example.voice.BUNDLED_STARTER_VOICE_ID,
      persona = com.example.ai.AssistantPersona.ARIA,
      preferBangla = false,
      isInstalled = { true }
    )
    assertEquals(com.example.voice.BUNDLED_STARTER_VOICE_ID, pickedForAria?.id)
  }

  @Test
  fun wakeWordDetector_matchesHeyAetherAndAriaAndOnlyTwoPersonasExist() {
    assertEquals(2, com.example.ai.AssistantPersona.entries.size)
    assertEquals(
      setOf(com.example.ai.AssistantPersona.AETHER, com.example.ai.AssistantPersona.ARIA),
      com.example.ai.AssistantPersona.entries.toSet()
    )

    val heyAether = com.example.system.WakeWordDetector.match(
      transcript = "Hey Aether open YouTube and play lofi",
      customWakeWord = "Hey Aether",
      persona = com.example.ai.AssistantPersona.AETHER
    )
    assertNotNull(heyAether)
    assertEquals("open YouTube and play lofi", heyAether?.trailingCommand)

    val heyAria = com.example.system.WakeWordDetector.match(
      transcript = "hey aria flashlight on",
      persona = com.example.ai.AssistantPersona.ARIA
    )
    assertNotNull(heyAria)
    assertEquals("flashlight on", heyAria?.trailingCommand)
  }

  @Test
  fun geminiCommandService_parsesSocialPostAndDndActions() {
    val service = com.example.ai.GeminiCommandService()
    val socialJson = """
      {
        "reply": "Opening Facebook and posting your status.",
        "needsScreenObservation": false,
        "steps": [
          {
            "stepId": "s0",
            "actionType": "POST_SOCIAL",
            "label": "Post on Facebook",
            "target": "Facebook",
            "textPayload": "I am happy today"
          },
          {
            "stepId": "s1",
            "actionType": "TOGGLE_DND",
            "label": "Enable DND",
            "boolValue": true
          }
        ]
      }
    """.trimIndent()
    val plan = service.parseCommandPlan(socialJson)
    assertEquals(2, plan.steps.size)
    assertEquals(com.example.ai.ActionType.POST_SOCIAL, plan.steps[0].actionType)
    assertEquals("Facebook", plan.steps[0].target)
    assertEquals("I am happy today", plan.steps[0].textPayload)
    assertEquals(com.example.ai.ActionType.TOGGLE_DND, plan.steps[1].actionType)
    assertTrue(plan.steps[1].boolValue)
  }

  @Test
  fun directGeminiRestClient_validatesApiKeyAndParsesCandidateJson() {
    var dynamicKey = ""
    var dynamicModel = ""
    val client = com.example.ai.DirectGeminiRestClient(
      apiKeyProvider = { dynamicKey },
      modelProvider = { dynamicModel }
    )
    assertFalse(client.isConfigured())
    assertEquals("gemini-2.5-flash", client.currentModelId())

    dynamicKey = "AIzaSyExampleKey1234567890"
    dynamicModel = "gemini-2.5-pro"
    assertTrue(client.isConfigured())
    assertEquals("gemini-2.5-pro", client.currentModelId())

    val rawResponse = """
      {
        "candidates": [
          {
            "content": {
              "parts": [
                {"text": "{\"reply\":\"Done\",\"steps\":[]}"}
              ]
            }
          }
        ]
      }
    """.trimIndent()
    assertEquals("{\"reply\":\"Done\",\"steps\":[]}", client.extractCandidateText(rawResponse))
  }

  @Test
  fun freeWebTools_detectsCurrentLocationTokensAndFormatsRainForecast() {
    val repo = com.example.tools.FreeWebToolsRepository()
    assertTrue(repo.isCurrentLocationToken("current"))
    assertTrue(repo.isCurrentLocationToken("current location"))
    assertTrue(repo.isCurrentLocationToken("my location"))
    assertTrue(repo.isCurrentLocationToken("আমার অবস্থান"))
    assertTrue(repo.isCurrentLocationToken(""))
    assertFalse(repo.isCurrentLocationToken("Dhaka"))

    val sampleForecast = """
      {
        "current": {
          "temperature_2m": 29.4,
          "apparent_temperature": 33.1,
          "relative_humidity_2m": 78,
          "wind_speed_10m": 12.4,
          "weather_code": 61
        },
        "daily": {
          "temperature_2m_max": [31.2],
          "temperature_2m_min": [24.5],
          "precipitation_probability_max": [80],
          "rain_sum": [6.4]
        }
      }
    """.trimIndent()
    val formatted = repo.formatOpenMeteoForecast("Dhaka, Bangladesh", sampleForecast)
    assertTrue(formatted.contains("Dhaka, Bangladesh"))
    assertTrue(formatted.contains("29°C"))
    assertTrue(formatted.contains("Rain"))
    assertTrue(formatted.contains("Rain chance today: 80%"))
    assertTrue(formatted.contains("6.4 mm rain"))
  }

  @Test
  fun offlineRouter_leavesWeatherQuestionsForLiveLocationWeatherTool() {
    assertNull(com.example.system.OfflineCommandRouter.tryMatch("ajke ki bristi hobe"))
    assertNull(com.example.system.OfflineCommandRouter.tryMatch("will it rain today"))
    assertNull(com.example.system.OfflineCommandRouter.tryMatch("what is the weather right now"))
  }

  @Test
  fun conversationTurnBuffer_mergesDbSeedBeforeRuntimeTurnsWithoutRaceLoss() {
    val buffer = com.example.core.ConversationTurnBuffer(maxCapacity = 4)

    // Simulate a live runtime turn arriving BEFORE async Room DB seed finishes
    buffer.addTurn("Turn on flashlight", "Flashlight turned ON.", 3000L)
    assertEquals(1, buffer.size())

    // Room returns newest-first (timestamp DESC) logs from previous app session
    val dbNewestFirst = listOf(
      com.example.core.ConversationTurn("Weather kemon?", "Dhaka is 29°C.", 2000L),
      com.example.core.ConversationTurn("Amar nam Mamun", "Nice to meet you, Mamun.", 1000L)
    )
    buffer.seedFromLogsIfNeeded(dbNewestFirst)

    // Chronological oldest -> newest must preserve all 3 turns in exact time order
    val pairs = buffer.recentPairsChronological(10)
    assertEquals(3, pairs.size)
    assertEquals("Amar nam Mamun" to "Nice to meet you, Mamun.", pairs[0])
    assertEquals("Weather kemon?" to "Dhaka is 29°C.", pairs[1])
    assertEquals("Turn on flashlight" to "Flashlight turned ON.", pairs[2])

    // Placeholder audio tags must not pollute conversation memory
    buffer.addTurn("Voice Audio (1.4s)", "Should be ignored")
    assertEquals(3, buffer.size())

    // Rolling window evicts oldest when exceeding capacity=4
    buffer.addTurn("Q4", "A4", 4000L)
    buffer.addTurn("Q5", "A5", 5000L)
    assertEquals(4, buffer.size())
    assertEquals("Weather kemon?", buffer.recentPairsChronological(10).first().first)
    assertTrue(buffer.formatPromptSummary(4).contains("Turn 4 User: Q5"))
  }

  @Test
  fun conversationFactExtractor_extractsMultilingualFactsAndIgnoresQuestionsOrEmotions() {
    val enName = com.example.core.ConversationFactExtractor.extractDurableFacts("My name is Mamun")
    assertTrue(enName.any { it.content == "User's name is Mamun" && it.category == "IDENTITY" })

    val banglishHome = com.example.core.ConversationFactExtractor.extractDurableFacts("Amar basa Mirpur Dhaka")
    assertTrue(banglishHome.any { it.content.contains("Mirpur Dhaka") })

    val banglaRemember = com.example.core.ConversationFactExtractor.extractDurableFacts("মনে রেখো কাল সকাল ১০টায় মিটিং")
    assertTrue(banglaRemember.any { it.content.contains("কাল সকাল ১০টায় মিটিং") })

    val pinRemember = com.example.core.ConversationFactExtractor.extractDurableFacts("Remember my gate pin is 4590")
    assertTrue(pinRemember.any { it.content == "my gate pin is 4590" })

    // Emotion phrase must NOT falsely create a name memory!
    val emotion = com.example.core.ConversationFactExtractor.extractDurableFacts("I am happy today")
    assertTrue("Expected no false identity from emotion statement", emotion.isEmpty())

    // Questions asking about memory must NOT be re-saved as facts!
    assertTrue(com.example.core.ConversationFactExtractor.extractDurableFacts("Amar nam ki?").isEmpty())
    assertTrue(com.example.core.ConversationFactExtractor.extractDurableFacts("What is my name?").isEmpty())

    val deduped = com.example.core.ConversationFactExtractor.deduplicateNewFacts(
      candidates = enName + pinRemember,
      existingContents = listOf("user's name is mamun.")
    )
    assertEquals(1, deduped.size)
    assertEquals("my gate pin is 4590", deduped.first().content)
  }

  @Test
  fun aiClients_buildTrueMultiTurnPayloadsForNvidiaAndDirectGemini() {
    val history = listOf(
      "Amar nam Mamun" to "Hello Mamun, I will remember your name.",
      "Turn on torch" to "Flashlight turned ON."
    )

    val nvidia = com.example.ai.NvidiaFallbackService(apiKey = "nvapi-test-1234567890")
    val nvJsonStr = nvidia.buildChatCompletionPayload(
      modelId = "nvidia/nemotron-3.5-bolt-e4b",
      systemPrompt = "You are Aether.",
      userPrompt = "Amar nam ki?",
      conversationHistory = history,
      stream = false
    )
    val nvObj = kotlinx.serialization.json.Json.parseToJsonElement(nvJsonStr)
      .let { it as kotlinx.serialization.json.JsonObject }
    val nvMessages = nvObj["messages"] as kotlinx.serialization.json.JsonArray
    // 1 system + 2*(2 history turns) + 1 current user = 6 messages
    assertEquals(6, nvMessages.size)
    assertEquals("system", (nvMessages[0] as kotlinx.serialization.json.JsonObject)["role"]?.toString()?.trim('"'))
    assertEquals("user", (nvMessages[1] as kotlinx.serialization.json.JsonObject)["role"]?.toString()?.trim('"'))
    assertEquals("assistant", (nvMessages[2] as kotlinx.serialization.json.JsonObject)["role"]?.toString()?.trim('"'))
    assertTrue(nvMessages[1].toString().contains("Amar nam Mamun"))
    assertTrue(nvMessages[5].toString().contains("Amar nam ki?"))

    val directGemini = com.example.ai.DirectGeminiRestClient(
      apiKeyProvider = { "AIzaSyTest1234567890" },
      modelProvider = { "gemini-2.5-flash" }
    )
    val gemJsonStr = directGemini.buildGenerateContentPayload(
      prompt = "Amar nam ki?",
      conversationHistory = history
    )
    val gemObj = kotlinx.serialization.json.Json.parseToJsonElement(gemJsonStr)
      .let { it as kotlinx.serialization.json.JsonObject }
    val contents = gemObj["contents"] as kotlinx.serialization.json.JsonArray
    assertEquals(5, contents.size)
    assertEquals("user", (contents[0] as kotlinx.serialization.json.JsonObject)["role"]?.toString()?.trim('"'))
    assertEquals("model", (contents[1] as kotlinx.serialization.json.JsonObject)["role"]?.toString()?.trim('"'))
    assertTrue(contents[4].toString().contains("Amar nam ki?"))
  }

  @Test
  fun taskDagExecutor_executesIndependentParallelBranchesConcurrentlyAndRespectsDependencies() = kotlinx.coroutines.runBlocking {
    val executor = com.example.core.TaskDagExecutor()
    val steps = listOf(
      com.example.ai.PlannedStep(
        stepId = "w",
        actionType = com.example.ai.ActionType.WEATHER_LOOKUP,
        label = "Weather",
        target = "current",
        isBackgroundParallel = true
      ),
      com.example.ai.PlannedStep(
        stepId = "k",
        actionType = com.example.ai.ActionType.WIKIPEDIA_LOOKUP,
        label = "Wiki",
        target = "Dhaka",
        isBackgroundParallel = true
      ),
      com.example.ai.PlannedStep(
        stepId = "t",
        actionType = com.example.ai.ActionType.TOGGLE_TORCH,
        label = "Torch ON",
        boolValue = true,
        isBackgroundParallel = true
      ),
      com.example.ai.PlannedStep(
        stepId = "final",
        actionType = com.example.ai.ActionType.SAVE_MEMORY,
        label = "Save Digest",
        textPayload = "Combined",
        dependsOn = listOf("w", "k")
      )
    )
    val nodes = executor.buildDag(steps)
    assertEquals(4, nodes.size)

    val executionOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
    var torchUpdated: Boolean? = null
    val startMs = System.currentTimeMillis()
    val summary = executor.executeDag(
      nodes = nodes,
      onTaskUpdate = { _, _, _, _, _ -> },
      onTorchStateChanged = { torchUpdated = it },
      executor = { step ->
        when (step.stepId) {
          "w" -> {
            kotlinx.coroutines.delay(220L)
            executionOrder.add("w")
            com.example.system.ActionExecutionResult(true, "Weather ready", toolOutputForSummary = "29°C Clear")
          }
          "k" -> {
            kotlinx.coroutines.delay(220L)
            executionOrder.add("k")
            com.example.system.ActionExecutionResult(true, "Wiki ready", toolOutputForSummary = "Capital of Bangladesh")
          }
          "t" -> {
            executionOrder.add("t")
            com.example.system.ActionExecutionResult(true, "Torch ON", torchStateChanged = true)
          }
          else -> {
            executionOrder.add(step.stepId)
            com.example.system.ActionExecutionResult(true, "Saved memory")
          }
        }
      }
    )
    val totalMs = System.currentTimeMillis() - startMs

    assertEquals(4, summary.completedCount)
    assertEquals(0, summary.failedCount)
    assertEquals(0, summary.timedOutCount)
    assertEquals(0, summary.skippedCount)
    assertEquals(2, summary.toolOutputs.size)
    assertEquals(true, torchUpdated)
    // Wave 1 (w=220ms and k=220ms) must run concurrently rather than sequentially (~440ms+)
    assertTrue("Expected parallel wave < 410ms, took ${totalMs}ms", totalMs < 410L)
    assertEquals("final", executionOrder.last())
  }

  @Test
  fun taskDagExecutor_handlesTimeoutsCascadeSkipsAndBreaksCycles() = kotlinx.coroutines.runBlocking {
    val executor = com.example.core.TaskDagExecutor()

    // Verify cycle breaking never deadlocks
    val cyclicSteps = listOf(
      com.example.ai.PlannedStep(stepId = "a", actionType = com.example.ai.ActionType.SPEAK_ONLY, label = "A", dependsOn = listOf("b")),
      com.example.ai.PlannedStep(stepId = "b", actionType = com.example.ai.ActionType.SPEAK_ONLY, label = "B", dependsOn = listOf("a"))
    )
    val acyclicNodes = executor.buildDag(cyclicSteps)
    val cycleSummary = executor.executeDag(
      nodes = acyclicNodes,
      onTaskUpdate = { _, _, _, _, _ -> },
      onTorchStateChanged = {},
      executor = { com.example.system.ActionExecutionResult(true, "ok") }
    )
    assertEquals(2, cycleSummary.completedCount)

    // Verify foreground chaining vs background parallel independence + timeout cascade skip
    val steps = listOf(
      com.example.ai.PlannedStep(
        stepId = "bg_ok",
        actionType = com.example.ai.ActionType.TOGGLE_DND,
        label = "DND",
        boolValue = true,
        isBackgroundParallel = true
      ),
      com.example.ai.PlannedStep(
        stepId = "slow_dep",
        actionType = com.example.ai.ActionType.WEATHER_LOOKUP,
        label = "Slow Tool",
        target = "Dhaka",
        timeoutMs = 150L,
        isBackgroundParallel = true
      ),
      com.example.ai.PlannedStep(
        stepId = "child",
        actionType = com.example.ai.ActionType.SAVE_MEMORY,
        label = "Dependent",
        dependsOn = listOf("slow_dep")
      )
    )
    val nodes = executor.buildDag(steps)
    val statusEvents = java.util.Collections.synchronizedList(mutableListOf<Pair<String, com.example.ai.TaskStatus>>())
    val summary = executor.executeDag(
      nodes = nodes,
      onTaskUpdate = { id, st, _, _, _ -> statusEvents.add(id to st) },
      onTorchStateChanged = {},
      executor = { step ->
        if (step.stepId == "slow_dep") {
          kotlinx.coroutines.delay(900L)
        }
        com.example.system.ActionExecutionResult(true, "Done")
      }
    )

    assertEquals(1, summary.completedCount)
    assertEquals(1, summary.timedOutCount)
    assertEquals(1, summary.skippedCount)
    assertTrue(statusEvents.any { it.first == "slow_dep" && it.second == com.example.ai.TaskStatus.TIMED_OUT })
    assertTrue(statusEvents.any { it.first == "child" && it.second == com.example.ai.TaskStatus.SKIPPED })
  }

  @Test
  fun taskDagExecutor_chainsForegroundUiActionsAfterAppLaunch() {
    val executor = com.example.core.TaskDagExecutor()
    val nodes = executor.buildDag(
      listOf(
        com.example.ai.PlannedStep(stepId = "open_wa", actionType = com.example.ai.ActionType.LAUNCH_APP, label = "Open WhatsApp", target = "WhatsApp"),
        com.example.ai.PlannedStep(stepId = "weather_bg", actionType = com.example.ai.ActionType.WEATHER_LOOKUP, label = "Weather", target = "current", isBackgroundParallel = true),
        com.example.ai.PlannedStep(stepId = "tap_send", actionType = com.example.ai.ActionType.UI_TAP_TEXT, label = "Tap Send", target = "Send")
      )
    )
    assertEquals(emptyList<String>(), nodes[0].dependencies)
    assertEquals(emptyList<String>(), nodes[1].dependencies)
    assertEquals(listOf("open_wa"), nodes[2].dependencies)
  }

  @Test
  fun backgroundStandbyPolicy_andBriefingScheduler_governBackgroundWork() {
    assertEquals(
      com.example.system.EchoGuardPolicy.StandbyDecision.START_WAKE_WORD,
      com.example.system.EchoGuardPolicy.resolveBackgroundStandby(
        wakeWordEnabled = true,
        micGranted = true,
        backgroundServicePaused = false,
        filteredMicCapturing = false,
        pauseWakeDuringMusic = true,
        musicPlaybackActive = false,
        isCoreIdle = true
      )
    )

    assertEquals(
      com.example.system.EchoGuardPolicy.StandbyDecision.PAUSED_FOR_MUSIC,
      com.example.system.EchoGuardPolicy.resolveBackgroundStandby(
        wakeWordEnabled = true,
        micGranted = true,
        backgroundServicePaused = false,
        filteredMicCapturing = false,
        pauseWakeDuringMusic = true,
        musicPlaybackActive = true,
        isCoreIdle = true
      )
    )

    assertEquals(
      com.example.system.EchoGuardPolicy.StandbyDecision.PAUSED_BY_USER,
      com.example.system.EchoGuardPolicy.resolveBackgroundStandby(
        wakeWordEnabled = true,
        micGranted = true,
        backgroundServicePaused = true,
        filteredMicCapturing = false,
        pauseWakeDuringMusic = false,
        musicPlaybackActive = false,
        isCoreIdle = true
      )
    )

    assertEquals(
      com.example.system.EchoGuardPolicy.StandbyDecision.YIELDS_TO_FILTERED_MIC,
      com.example.system.EchoGuardPolicy.resolveBackgroundStandby(
        wakeWordEnabled = true,
        micGranted = true,
        backgroundServicePaused = false,
        filteredMicCapturing = true,
        pauseWakeDuringMusic = false,
        musicPlaybackActive = false,
        isCoreIdle = true
      )
    )

    // Verify daily briefing scheduler same-day future vs next-day rollover in UTC
    val utc = java.util.TimeZone.getTimeZone("UTC")
    val calToday = java.util.Calendar.getInstance(utc).apply {
      set(2026, java.util.Calendar.NOVEMBER, 23, 7, 30, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    val sameDayDelay = com.example.briefing.BriefingScheduler.computeDailyBriefingInitialDelayMs(
      nowMillis = calToday.timeInMillis,
      hour = 8,
      minute = 0,
      timeZone = utc
    )
    assertEquals(30L * 60_000L, sameDayDelay)

    val calPassed = java.util.Calendar.getInstance(utc).apply {
      set(2026, java.util.Calendar.NOVEMBER, 23, 9, 0, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    val nextDayDelay = com.example.briefing.BriefingScheduler.computeDailyBriefingInitialDelayMs(
      nowMillis = calPassed.timeInMillis,
      hour = 8,
      minute = 0,
      timeZone = utc
    )
    assertEquals(23L * 3600_000L, nextDayDelay)

    // Past reminder clamps to >= 1000 ms for immediate WorkManager dispatch
    assertEquals(1_000L, com.example.briefing.BriefingScheduler.computeReminderDelayMs(1000L, 5000L))
  }

  @Test
  fun offlineScreenEngine_distillsUiTreeClassifiesScenesAndSuppressesRedundantBitmaps() {
    val rawTree = buildString {
      appendLine("Foreground package: com.google.android.youtube (1080x2400)")
      appendLine("[500,500|mid] \"ViewGroup\"")
      appendLine("[500,500|mid] \"FrameLayout\"")
      appendLine("[150,240|top] (click) \"Home\"")
      appendLine("[360,240|top] (click) \"Videos\"")
      appendLine("[360,240|top] (click) \"Videos\"") // duplicate inside same spatial bucket
      appendLine("[570,240|top] (click) \"Shorts\"")
      appendLine("[780,240|top] (click) \"Playlists\"")
      appendLine("[500,410|mid] (click) \"Subscribe\"")
      appendLine("[480,620|mid] (click) \"Amazing Aether 3D Walkthrough Tutorial 10 minutes\"")
    }

    val compact = com.example.vision.OfflineScreenUnderstandingEngine.compactAccessibilitySummary(rawTree)
    assertFalse(compact.contains("ViewGroup"))
    assertFalse(compact.contains("FrameLayout"))
    assertEquals(1, Regex("\"Videos\"").findAll(compact).count())
    assertTrue(compact.length < rawTree.length)

    val analysis = com.example.vision.OfflineScreenUnderstandingEngine.buildOfflineAnalysis(
      packageName = "com.google.android.youtube",
      rawUiSummary = rawTree,
      nodeCount = 9,
      ocrLines = emptyList(),
      ocrSummary = "",
      visualLabels = listOf("Screenshot", "Text"),
      detectedObjects = listOf(
        com.example.vision.DetectedVisualElement(
          label = "VisualCard",
          confidence = 0.85f,
          centerXNorm = 480,
          centerYNorm = 620,
          widthNorm = 820,
          heightNorm = 260,
          isMediaBox = true
        )
      ),
      userPrompt = "YouTube a novaking entertainment channel er Videos tab open koro",
      visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
      hasSourceBitmap = true
    )

    assertEquals(com.example.vision.ScreenSceneType.CHANNEL_PROFILE, analysis.sceneType)
    assertTrue(analysis.semanticConfidence >= 0.65f)
    assertFalse("Should omit cloud bitmap when offline vision confidence is high", analysis.shouldAttachBitmapToCloud)
    assertTrue("Expected >= 760 saved tokens when suppressing bitmap", analysis.estimatedTokensSaved >= 760)
    assertTrue(analysis.buildCompactVisionHeader().contains("OFFLINE SCREEN VISION (<50MB MLKit)"))

    // Explicit photo/color prompt MUST still attach pixels!
    val photoAnalysis = com.example.vision.OfflineScreenUnderstandingEngine.buildOfflineAnalysis(
      packageName = "com.google.android.apps.photos",
      rawUiSummary = rawTree,
      nodeCount = 9,
      ocrLines = emptyList(),
      ocrSummary = "",
      visualLabels = listOf("Fashion", "Photo"),
      detectedObjects = emptyList(),
      userPrompt = "What color is the shirt in this photo?",
      visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
      hasSourceBitmap = true
    )
    assertTrue("Color/photo Q&A must attach cloud bitmap", photoAnalysis.shouldAttachBitmapToCloud)
  }

  @Test
  fun offlineScreenEngine_resolvesYouTubeMultiStepHopsWithZeroCloudTokens() {
    val searchPrompt = "YouTube a novaking entertainment search kore channel a giye first video play koro"
    assertEquals(
      "novaking entertainment",
      com.example.vision.OfflineScreenUnderstandingEngine.extractSearchQueryFromPrompt(searchPrompt)
    )

    // Hop 1: Tap Videos tab offline
    val channelUi = """
      Foreground package: com.google.android.youtube (1080x2400)
      [150,240|top] (click) "Home"
      [360,240|top] (click) "Videos"
      [570,240|top] (click) "Shorts"
      [780,240|top] (click) "Playlists"
      [500,190|top] (click) "Subscribe"
      [500,540|mid] (click) "First Cool Gameplay Video by Novaking"
    """.trimIndent()
    val channelAnalysis = com.example.vision.OfflineScreenUnderstandingEngine.buildOfflineAnalysis(
      packageName = "com.google.android.youtube",
      rawUiSummary = channelUi,
      nodeCount = 6,
      ocrLines = emptyList(),
      ocrSummary = "",
      visualLabels = listOf("Screenshot"),
      detectedObjects = emptyList(),
      userPrompt = searchPrompt,
      visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
      hasSourceBitmap = true
    )
    val videosTap = com.example.vision.OfflineScreenUnderstandingEngine.tryResolveNextHopOffline(
      activeSubGoal = "Open Videos section",
      userPrompt = searchPrompt,
      analysis = channelAnalysis
    )
    assertNotNull(videosTap)
    assertEquals(com.example.ai.ActionType.UI_TAP_COORDINATES, videosTap?.step?.actionType)
    assertEquals(360, videosTap?.step?.intValue)
    assertEquals(240, videosTap?.step?.secondaryIntValue)

    // Hop 2: Play first video via offline node OR ML Kit Object Detection box when Accessibility hides labels
    val hiddenTreeAnalysis = com.example.vision.OfflineScreenUnderstandingEngine.buildOfflineAnalysis(
      packageName = "com.google.android.youtube",
      rawUiSummary = "Foreground package: com.google.android.youtube (1080x2400)",
      nodeCount = 1,
      ocrLines = emptyList(),
      ocrSummary = "",
      visualLabels = listOf("Video"),
      detectedObjects = listOf(
        com.example.vision.DetectedVisualElement(
          label = "VisualCard",
          confidence = 0.80f,
          centerXNorm = 500,
          centerYNorm = 420,
          widthNorm = 900,
          heightNorm = 300,
          isMediaBox = true
        )
      ),
      userPrompt = searchPrompt,
      visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
      hasSourceBitmap = true
    )
    val playTap = com.example.vision.OfflineScreenUnderstandingEngine.tryResolveNextHopOffline(
      activeSubGoal = "Play target video",
      userPrompt = searchPrompt,
      analysis = hiddenTreeAnalysis
    )
    assertNotNull(playTap)
    assertEquals(500, playTap?.step?.intValue)
    assertEquals(420, playTap?.step?.secondaryIntValue)
  }

  @Test
  fun screenLoopGuard_distinguishesFacebookFromFacebookLite() {
    val fbKeywords = com.example.core.ScreenLoopGuard.expectedPackageKeywords("Facebook")
    assertTrue(fbKeywords.contains("katana"))
    assertFalse(fbKeywords.contains("facebook.lite"))

    val fbLiteKeywords = com.example.core.ScreenLoopGuard.expectedPackageKeywords("Facebook Lite")
    assertTrue(fbLiteKeywords.contains("facebook.lite"))
    assertFalse(fbLiteKeywords.contains("katana"))

    val msgLiteKeywords = com.example.core.ScreenLoopGuard.expectedPackageKeywords("Messenger Lite")
    assertTrue(msgLiteKeywords.contains("mlite"))
  }

  @Test
  fun offlineScreenEngine_resolvesAudienceShareNextPopupsAndLiteVisionFallback() {
    // 1. Facebook Lite triggers compact Vision fallback even in OFFLINE_FIRST
    val liteAnalysis = com.example.vision.OfflineScreenUnderstandingEngine.buildOfflineAnalysis(
      packageName = "com.facebook.lite",
      rawUiSummary = "Foreground package: com.facebook.lite (1080x2400)",
      nodeCount = 2,
      ocrLines = listOf(com.example.vision.OcrLineItem("Next", 900, 110)),
      ocrSummary = "",
      visualLabels = listOf("Mobile phone"),
      detectedObjects = emptyList(),
      userPrompt = "Facebook Lite a post koro Hey guys I am Aether",
      visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
      hasSourceBitmap = true
    )
    assertTrue(com.example.vision.OfflineScreenUnderstandingEngine.isLiteOrCanvasApp("com.facebook.lite"))
    assertTrue("Lite app should attach compact Vision crop", liteAnalysis.shouldAttachBitmapToCloud)

    // 2. Audience/Share stage classifies and resolves 'Share' offline without touching Discard
    val shareUi = """
      Foreground package: com.facebook.katana (1080x2400)
      [500,120|top] "Post settings"
      [250,310|mid] (click) "Public"
      [500,520|mid] (click) "Discard post"
      [880,920|bot] (click) "Share now"
    """.trimIndent()
    val shareAnalysis = com.example.vision.OfflineScreenUnderstandingEngine.buildOfflineAnalysis(
      packageName = "com.facebook.katana",
      rawUiSummary = shareUi,
      nodeCount = 4,
      ocrLines = emptyList(),
      ocrSummary = "",
      visualLabels = listOf("Screenshot"),
      detectedObjects = emptyList(),
      userPrompt = "Write a post on Facebook Hey guys I am Aether",
      visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
      hasSourceBitmap = false
    )
    assertEquals(com.example.vision.ScreenSceneType.AUDIENCE_OR_SHARE_STAGE, shareAnalysis.sceneType)
    val shareHop = com.example.vision.OfflineScreenUnderstandingEngine.tryResolveNextHopOffline(
      activeSubGoal = "Tap Next or Share",
      userPrompt = "Write a post on Facebook Hey guys I am Aether",
      analysis = shareAnalysis
    )
    assertNotNull(shareHop)
    assertEquals(880, shareHop?.step?.intValue)
    assertEquals(920, shareHop?.step?.secondaryIntValue)

    // 3. Blocking popup dismisses cleanly using 'Not now'
    val popupUi = """
      Foreground package: com.facebook.lite (1080x2400)
      [500,400|mid] "Save login info?"
      [350,620|mid] (click) "Not now"
      [680,620|mid] (click) "Save"
    """.trimIndent()
    val popupAnalysis = com.example.vision.OfflineScreenUnderstandingEngine.buildOfflineAnalysis(
      packageName = "com.facebook.lite",
      rawUiSummary = popupUi,
      nodeCount = 3,
      ocrLines = emptyList(),
      ocrSummary = "",
      visualLabels = emptyList(),
      detectedObjects = emptyList(),
      userPrompt = "Facebook Lite a post koro",
      visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
      hasSourceBitmap = false
    )
    assertEquals(com.example.vision.ScreenSceneType.DIALOG_MODAL, popupAnalysis.sceneType)
    val dismissHop = com.example.vision.OfflineScreenUnderstandingEngine.tryResolveNextHopOffline(
      activeSubGoal = "Complete action",
      userPrompt = "Facebook Lite a post koro",
      analysis = popupAnalysis
    )
    assertNotNull(dismissHop)
    assertEquals(350, dismissHop?.step?.intValue)
  }

  @Test
  fun taskDagExecutor_chainsStepOutputPlaceholdersRetriesAndOrdersPostSocial() = kotlinx.coroutines.runBlocking {
    val executor = com.example.core.TaskDagExecutor()
    val steps = listOf(
      com.example.ai.PlannedStep(
        stepId = "s0",
        actionType = com.example.ai.ActionType.WEATHER_LOOKUP,
        label = "Fetch weather",
        target = "Dhaka",
        isBackgroundParallel = true
      ),
      com.example.ai.PlannedStep(
        stepId = "s1",
        actionType = com.example.ai.ActionType.POST_SOCIAL,
        label = "Post on Facebook Lite",
        target = "Facebook Lite",
        textPayload = "Weather right now: {{s0}}",
        dependsOn = listOf("s0")
      )
    )
    val nodes = executor.buildDag(steps)
    assertEquals(70_000L, nodes[1].timeoutMs)

    var s0Attempts = 0
    var capturedSocialText = ""
    val summary = executor.executeDag(
      nodes = nodes,
      onTaskUpdate = { _, _, _, _, _ -> },
      onTorchStateChanged = {},
      executor = { step ->
        when (step.stepId) {
          "s0" -> {
            s0Attempts++
            if (s0Attempts == 1) {
              com.example.system.ActionExecutionResult(false, "Transient network hiccup")
            } else {
              com.example.system.ActionExecutionResult(
                success = true,
                detail = "30°C Sunny in Dhaka",
                toolOutputForSummary = "30°C Sunny in Dhaka"
              )
            }
          }
          else -> {
            capturedSocialText = step.textPayload
            com.example.system.ActionExecutionResult(true, "Posted", toolOutputForSummary = "Posted on Facebook Lite")
          }
        }
      }
    )

    assertEquals(2, s0Attempts)
    assertEquals(2, summary.completedCount)
    assertEquals(0, summary.failedCount)
    assertEquals("Weather right now: 30°C Sunny in Dhaka", capturedSocialText)
  }
}
