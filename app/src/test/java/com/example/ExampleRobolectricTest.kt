package com.example

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ActionType
import com.example.ai.AssistantPersona
import com.example.ai.CoreMode
import com.example.ai.GeminiCommandService
import com.example.ai.PlannedStep
import com.example.ai.SpeechLocaleOption
import com.example.core.AssistantUiState
import com.example.data.local.CommandLogEntity
import com.example.data.local.QuickContactEntity
import com.example.data.local.VaultRepository
import com.example.system.AndroidActionRouter
import com.example.system.DeviceTelemetryProvider
import com.example.system.PermissionsManager
import com.example.tools.FreeWebToolsRepository
import com.example.ui.components.CoreStatusAndReplyOverlay
import com.example.ui.components.TopTelemetryBar
import com.example.ui.components.VoiceDock
import com.example.ui.gl.GeometryBuilder
import com.example.ui.gl.JarvisRenderer
import com.example.ui.gl.ShaderUtils
import com.example.ui.theme.Aether3DTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name and branding resources match Aether 3D`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Aether 3D", context.getString(R.string.app_name))
        assertTrue(context.getString(R.string.accessibility_service_label).contains("Aether 3D"))
    }

    @Test
    fun `3d avatar geometry builders produce valid non-empty finite meshes`() {
        val head = GeometryBuilder.buildAvatarHeadMesh()
        val headWire = GeometryBuilder.buildAvatarHeadWireframe()
        val jaw = GeometryBuilder.buildAvatarJawMesh()
        val visor = GeometryBuilder.buildAvatarVisorEyesMesh()
        val torso = GeometryBuilder.buildAvatarTorsoMesh()
        val chestCore = GeometryBuilder.buildAvatarChestCoreMesh()
        val ring = GeometryBuilder.buildSegmentedArcRing(1.18f)
        val prism = GeometryBuilder.buildUnitPrismTriangles()
        val grid = GeometryBuilder.buildPerspectiveFloorGrid()
        val particles = GeometryBuilder.buildOrbitalParticles(140)

        val buffers = listOf(head, headWire, jaw, visor, torso, chestCore, ring, prism, grid, particles)
        buffers.forEach { mesh ->
            assertTrue("Expected positive vertexCount", mesh.vertexCount > 0)
            mesh.buffer.position(0)
            while (mesh.buffer.hasRemaining()) {
                assertFalse("Unexpected NaN/Inf in mesh buffer", mesh.buffer.get().isNaN())
            }
            mesh.buffer.position(0)
        }

        // Verify Renderer orbit/zoom camera math bounds
        val renderer = JarvisRenderer()
        renderer.addOrbitDrag(120f, 900f)
        assertTrue(renderer.userPitchOffset in -55f..55f)
        renderer.applyZoomScale(10f)
        assertTrue(renderer.userZoomFactor in 0.65f..1.65f)
        renderer.resetCamera()
        assertEquals(0f, renderer.userYawOffset, 0.001f)
        assertEquals(1f, renderer.userZoomFactor, 0.001f)
    }

    @Test
    fun `shaders enforce matching mediump precisions on shared uniforms and varyings`() {
        assertTrue(ShaderUtils.CORE_VERTEX_SHADER.contains("uniform mediump float uTime;"))
        assertTrue(ShaderUtils.CORE_FRAGMENT_SHADER.contains("uniform mediump float uTime;"))
        assertTrue(ShaderUtils.CORE_VERTEX_SHADER.contains("uniform mediump float uAudioLevel;"))
        assertTrue(ShaderUtils.CORE_FRAGMENT_SHADER.contains("uniform mediump float uAudioLevel;"))
        assertTrue(ShaderUtils.CORE_VERTEX_SHADER.contains("varying mediump vec3 vNormal;"))
        assertTrue(ShaderUtils.CORE_FRAGMENT_SHADER.contains("varying mediump vec3 vNormal;"))
        assertTrue(ShaderUtils.LINE_VERTEX_SHADER.contains("varying mediump vec3 vWorldPos;"))
        assertTrue(ShaderUtils.LINE_FRAGMENT_SHADER.contains("varying mediump vec3 vWorldPos;"))
        assertTrue(ShaderUtils.PARTICLE_VERTEX_SHADER.contains("varying mediump float vAlpha;"))
        assertTrue(ShaderUtils.PARTICLE_FRAGMENT_SHADER.contains("varying mediump float vAlpha;"))
    }

    @Test
    fun `gemini command plan parser handles multitasking json and explicit nulls safely`() {
        val service = GeminiCommandService()
        val rawJson = """
            {
              "reply": "Turning on flashlight and checking weather in Delhi.",
              "needsScreenObservation": false,
              "continueLoopAfterAction": false,
              "steps": [
                {
                  "actionType": "TOGGLE_TORCH",
                  "label": "Flashlight On",
                  "target": null,
                  "textPayload": null,
                  "boolValue": true,
                  "isBackgroundParallel": true,
                  "isSensitive": false
                },
                {
                  "actionType": "WEATHER_LOOKUP",
                  "label": "Delhi Weather",
                  "target": "Delhi",
                  "isBackgroundParallel": true
                }
              ]
            }
        """.trimIndent()

        val plan = service.parseCommandPlan(rawJson)
        assertEquals(2, plan.steps.size)
        assertEquals(ActionType.TOGGLE_TORCH, plan.steps[0].actionType)
        assertEquals("", plan.steps[0].target)
        assertEquals("", plan.steps[0].textPayload)
        assertTrue(plan.steps[0].boolValue)
        assertTrue(plan.steps[0].isBackgroundParallel)
        assertEquals(ActionType.WEATHER_LOOKUP, plan.steps[1].actionType)
        assertEquals("Delhi", plan.steps[1].target)
    }

    @Test
    fun `vault repository persists logs memories routines contacts and preferences`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = VaultRepository(context)

        // Logs
        vault.insertLog(
            CommandLogEntity(
                userPrompt = "Turn on flashlight",
                assistantReply = "Flashlight enabled",
                personaUsed = "AETHER",
                sourceMode = "FOREGROUND",
                hadScreenVision = false,
                tasksSummary = "Flashlight On: DONE",
                executionStatus = "EXECUTED"
            )
        )

        // Memories & Contacts
        val memId = vault.saveMemory("Gate code is 4590", "FACT")
        vault.toggleMemoryPinned(memId, true)
        vault.saveQuickContact(
            QuickContactEntity(
                aliasName = "Mom",
                displayName = "Mom",
                phoneNumber = "+919876543210"
            )
        )

        // Routines
        val routineId = vault.saveRoutine("Gym Mode", "Play pump mix", "Open YouTube and set volume 80")
        vault.recordRoutineUsed(routineId)

        // Preferences
        val savedPrefs = vault.loadPreferences().copy(
            persona = AssistantPersona.ARIA,
            speechLocale = SpeechLocaleOption.BN_BD,
            continuousLoopEnabled = true
        )
        vault.savePreferences(savedPrefs)
        val reloadedPrefs = vault.loadPreferences()

        assertTrue(vault.getTopMemories().any { it.content.contains("4590") })
        assertTrue(vault.getContactsSnapshot().any { it.aliasName == "Mom" && it.phoneNumber == "+919876543210" })
        assertTrue(vault.getRoutinesSnapshot().any { it.triggerPhrase == "Gym Mode" && it.usageCount >= 1 })
        assertEquals(AssistantPersona.ARIA, reloadedPrefs.persona)
        assertEquals(SpeechLocaleOption.BN_BD, reloadedPrefs.speechLocale)
        assertEquals(2, SpeechLocaleOption.entries.size)
        assertTrue(reloadedPrefs.continuousLoopEnabled)
    }

    @Test
    fun `permissions manager reports all eleven capabilities and valid intents`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PermissionsManager(context)
        val snapshot = manager.checkSnapshot()

        assertEquals(11, snapshot.totalCount)
        assertEquals(11, snapshot.toItemList().size)
        assertTrue(manager.runtimePermissionsToRequest().isNotEmpty())
        assertEquals(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            manager.buildSpecialSettingsIntent("overlay").action
        )
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            manager.buildSpecialSettingsIntent("accessibility").action
        )
    }

    @Test
    fun `action router executes local memory routine and accessibility guard cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = VaultRepository(context)
        val telemetry = DeviceTelemetryProvider(context)
        val router = AndroidActionRouter(context, telemetry, vault, FreeWebToolsRepository())

        val memRes = router.executeStep(
            PlannedStep(ActionType.SAVE_MEMORY, "Save Note", textPayload = "WiFi pin is 7788"),
            splitScreenEnabled = false
        )
        assertTrue(memRes.success)
        assertTrue(vault.getTopMemories().any { it.content.contains("7788") })

        val uiTapRes = router.executeStep(
            PlannedStep(ActionType.UI_TAP_TEXT, "Tap Skip", target = "Skip"),
            splitScreenEnabled = false
        )
        assertFalse(uiTapRes.success)
        assertTrue(uiTapRes.detail.contains("Trusted Screen Agent"))

        val speakRes = router.executeStep(
            PlannedStep(ActionType.SPEAK_ONLY, "Reply"),
            splitScreenEnabled = false
        )
        assertTrue(speakRes.success)
    }

    @Test
    fun `hud compose chrome renders telemetry overlay and voice dock actions`() {
        var personaClicks = 0
        var micClicks = 0
        var submittedCmd = ""
        val ampFlow = MutableStateFlow(0.4f)
        val state = AssistantUiState(
            coreMode = CoreMode.IDLE,
            latestReply = "3D Avatar online and standing by."
        )

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                androidx.compose.foundation.layout.Column {
                    TopTelemetryBar(
                        uiState = state,
                        onCyclePersona = { personaClicks++ },
                        onOpenPermissions = {},
                        onOpenVault = {}
                    )
                    CoreStatusAndReplyOverlay(
                        uiState = state,
                        onAbortTasks = {},
                        onConfirmSensitive = {},
                        onDismissSensitive = {}
                    )
                    VoiceDock(
                        uiState = state,
                        audioAmplitudeFlow = ampFlow,
                        onMicClick = { micClicks++ },
                        onSubmitText = { submittedCmd = it },
                        onToggleBackgroundService = {},
                        onToggleTts = {},
                        onOpenSettings = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("persona_cycle_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("mic_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("bottom_settings_button").assertIsDisplayed()
        composeRule.onNodeWithTag("command_input").assertIsDisplayed()
        composeRule.onNodeWithText("3D Avatar online and standing by.").assertIsDisplayed()

        assertEquals(1, personaClicks)
        assertEquals(1, micClicks)
        assertNotNull(submittedCmd)
    }

    @Test
    fun `gemini unconstrained fallback parser extracts fenced json and plain replies`() {
        val service = GeminiCommandService()
        val fenced = """
            Sure, executing right away:
            ```json
            {
              "reply": "Opening Settings now.",
              "needsScreenObservation": false,
              "steps": [
                {"actionType": "OPEN_SETTINGS", "label": "Open WiFi", "target": "wifi"}
              ]
            }
            ```
        """.trimIndent()

        val parsed = service.parseCommandPlanOrPlainReply(fenced)
        assertEquals("Opening Settings now.", parsed.reply)
        assertEquals(1, parsed.steps.size)
        assertEquals(ActionType.OPEN_SETTINGS, parsed.steps[0].actionType)
        assertEquals("wifi", parsed.steps[0].target)

        val plain = service.parseCommandPlanOrPlainReply("The capital of India is New Delhi.")
        assertEquals("The capital of India is New Delhi.", plain.reply)
        assertEquals(ActionType.SPEAK_ONLY, plain.steps.first().actionType)
    }

    @Test
    fun `nvidia fallback service validates key placeholder and parses chat completion json`() {
        val unconfigured = com.example.ai.NvidiaFallbackService(apiKey = "NVIDIA_API_KEY_DEFAULT_VALUE")
        assertFalse(unconfigured.isConfigured())

        val blankKey = com.example.ai.NvidiaFallbackService(apiKey = "   ")
        assertFalse(blankKey.isConfigured())

        val configured = com.example.ai.NvidiaFallbackService(apiKey = "nvapi-sample-valid-token")
        assertTrue(configured.isConfigured())

        val mockNvidiaResponse = """
            {
              "id": "chatcmpl-123",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "{\"reply\":\"Torch turned on.\",\"steps\":[{\"actionType\":\"TOGGLE_TORCH\",\"label\":\"Torch On\",\"boolValue\":true}]}"
                  }
                }
              ]
            }
        """.trimIndent()
        val extracted = configured.extractAssistantContent(mockNvidiaResponse)
        assertTrue(extracted.contains("TOGGLE_TORCH"))
        val plan = GeminiCommandService(configured).parseCommandPlanOrPlainReply(extracted)
        assertEquals("Torch turned on.", plan.reply)
        assertEquals(ActionType.TOGGLE_TORCH, plan.steps.first().actionType)
        assertTrue(plan.steps.first().boolValue)
    }

    @Test
    fun `human cartoon avatar renders with accessibility test tag for personas`() {
        val ampFlow = MutableStateFlow(0.55f)
        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.ARIA) {
                com.example.ui.components.HumanCartoonAvatar(
                    coreMode = CoreMode.SPEAKING,
                    persona = AssistantPersona.ARIA,
                    audioAmplitudeFlow = ampFlow,
                    headYawDeg = 8f,
                    headPitchDeg = -4f,
                    zoomScale = 1.0f
                )
            }
        }
        composeRule.onNodeWithTag("human_cartoon_avatar").assertIsDisplayed()
    }

    @Test
    fun `compound in-app search parses cleanly and realistic portrait drawables resolve for all personas`() {
        val service = GeminiCommandService()
        val compoundJson = """
            {
              "reply": "Opening YouTube and searching Nova king entertainment.",
              "needsScreenObservation": false,
              "steps": [
                {
                  "actionType": "SEARCH_IN_APP",
                  "label": "Search YouTube",
                  "target": "YouTube",
                  "textPayload": "Nova king entertainment"
                }
              ]
            }
        """.trimIndent()
        val plan = service.parseCommandPlanOrPlainReply(compoundJson)
        assertEquals(ActionType.SEARCH_IN_APP, plan.steps.first().actionType)
        assertEquals("YouTube", plan.steps.first().target)
        assertEquals("Nova king entertainment", plan.steps.first().textPayload)

        val context = ApplicationProvider.getApplicationContext<Context>()
        AssistantPersona.entries.forEach { persona ->
            val resId = com.example.ui.components.avatarDrawableFor(persona)
            assertNotNull(context.resources.getDrawable(resId, null))
        }
        assertEquals(
            setOf(SpeechLocaleOption.EN_US, SpeechLocaleOption.BN_BD),
            SpeechLocaleOption.entries.toSet()
        )
    }

    @Test
    fun `gemini free tier billing error is treated as non-retryable and formatted concisely`() {
        val service = GeminiCommandService()
        val billingError = IllegalStateException(
            "The Gemini API free tier is not available for this project. To use the Gemini API, please set up billing to upgrade to the paid tier at https://aistudio.google.com/billing."
        )
        assertTrue(service.isNonRetryableGeminiError(billingError))
        val readable = service.extractReadableError(billingError)
        assertTrue(readable.contains("aistudio.google.com/billing"))
    }

    @Test
    fun `in-app nvidia api key dialog saves key model and direct mode into vault preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = VaultRepository(context)
        var currentKey = ""
        val dynamicNvidia = com.example.ai.NvidiaFallbackService(
            apiKeyProvider = { currentKey }
        )
        assertFalse(dynamicNvidia.isConfigured())

        var savedKey = ""
        var savedModel = ""
        var savedPrimary = false

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                com.example.ui.components.NvidiaApiDialog(
                    preferences = com.example.data.local.AssistantPreferences(),
                    isConfigured = false,
                    onDismiss = {},
                    onSave = { k, m, p, gk, gm ->
                        savedKey = k
                        savedModel = m
                        savedPrimary = p
                        vault.savePreferences(
                            vault.loadPreferences().copy(
                                nvidiaApiKey = k,
                                nvidiaModel = m,
                                preferNvidiaPrimary = p,
                                geminiApiKey = gk,
                                geminiModel = gm
                            )
                        )
                        currentKey = k
                    }
                )
            }
        }

        composeRule.onNodeWithTag("nvidia_key_field").performTextReplacement("nvapi-test-inapp-key-999")
        composeRule.onNodeWithTag("nvidia_model_field").performTextReplacement("nvidia/llama-3.1-nemotron-70b-instruct")
        composeRule.onNodeWithTag("nvidia_save_button").performClick()

        assertEquals("nvapi-test-inapp-key-999", savedKey)
        assertEquals("nvidia/llama-3.1-nemotron-70b-instruct", savedModel)
        assertTrue(savedPrimary)
        assertTrue(dynamicNvidia.isConfigured())

        val reloaded = vault.loadPreferences()
        assertEquals("nvapi-test-inapp-key-999", reloaded.nvidiaApiKey)
        assertEquals("nvidia/llama-3.1-nemotron-70b-instruct", reloaded.nvidiaModel)
        assertTrue(reloaded.preferNvidiaPrimary)
    }

    @Test
    fun `nvidia model discovery filters non-chat models and migrates eol llama-3_3`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = VaultRepository(context)
        vault.savePreferences(
            vault.loadPreferences().copy(nvidiaModel = "meta/llama-3.3-70b-instruct")
        )
        val migrated = vault.loadPreferences()
        assertEquals(
            com.example.data.local.AssistantPreferences.DEFAULT_NVIDIA_MODEL_ID,
            migrated.nvidiaModel
        )

        val service = com.example.ai.NvidiaFallbackService(apiKey = "nvapi-sample-valid-token")
        val sampleModelsJson = """
            {
              "object": "list",
              "data": [
                {"id": "nvidia/nv-embedqa-e5-v5"},
                {"id": "nvidia/llama-3.2-nv-rerankqa-1b-v2"},
                {"id": "qwen/qwen3-coder-480b-a35b-instruct"},
                {"id": "nvidia/nemotron-3.5-bolt-e4b"}
              ]
            }
        """.trimIndent()
        val ranked = service.parseAndRankChatModels(sampleModelsJson)
        assertEquals(
            listOf("nvidia/nemotron-3.5-bolt-e4b", "qwen/qwen3-coder-480b-a35b-instruct"),
            ranked
        )
    }

    @Test
    fun `streaming reply extractor and nvidia sse delta parse incremental tokens cleanly`() {
        val partial1 = """{"reply":"Opening YouTube"""
        assertEquals("Opening YouTube", com.example.ai.StreamingReplyExtractor.extractPartialReply(partial1))

        val partial2 = """{"reply":"Say \"Hello\"\nnow","steps":["""
        assertEquals("Say \"Hello\" now", com.example.ai.StreamingReplyExtractor.extractPartialReply(partial2))

        val plain = "It is currently 28 degrees in Dhaka."
        assertEquals(plain, com.example.ai.StreamingReplyExtractor.extractPartialReply(plain))

        val nvidia = com.example.ai.NvidiaFallbackService(apiKey = "nvapi-sample-valid-token")
        val delta = nvidia.extractSseDeltaContent("""{"choices":[{"delta":{"content":"Hello "}}]}""")
        assertEquals("Hello ", delta)
    }

    @Test
    fun `screen vision processor crops targeted regions and formats normalized ocr coordinates`() {
        val processor = com.example.vision.ScreenVisionProcessor()
        val topSpec = processor.computeTargetCropRect(1080, 2400, "Read notification at the top")
        assertEquals("Top Region", topSpec.regionLabel)
        assertTrue(topSpec.areaReductionPercent >= 50)
        assertTrue(topSpec.rect.bottom <= 1050)

        val dialogSpec = processor.computeTargetCropRect(1080, 2400, "Tap the dialog confirm button")
        assertEquals("Center Focus", dialogSpec.regionLabel)
        assertTrue(dialogSpec.areaReductionPercent >= 35)

        val anchor = android.graphics.Rect(100, 400, 500, 800)
        val anchorSpec = processor.computeTargetCropRect(1080, 2400, "tap login", anchorRect = anchor)
        assertEquals("Focused UI Region", anchorSpec.regionLabel)
        assertTrue(anchorSpec.areaReductionPercent > 50)

        val src = android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888)
        val cropped = processor.cropAndScaleRegion(src, topSpec, maxDimension = 720)
        assertTrue(maxOf(cropped.width, cropped.height) <= 720)
        src.recycle()
        if (cropped !== src) cropped.recycle()

        val ocrFormatted = processor.formatOcrSummary(
            listOf(
                com.example.vision.OcrLineItem("Allow", 650, 720),
                com.example.vision.OcrLineItem("Settings", 120, 90)
            )
        )
        assertTrue(ocrFormatted.contains("[650,720] \"Allow\""))
        assertTrue(ocrFormatted.contains("[120,90] \"Settings\""))
    }

    @Test
    fun `task dag executor resolves dependencies, breaks cycles, enforces timeouts, and cascades skips`() = runBlocking {
        val executor = com.example.core.TaskDagExecutor()

        val logOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val steps = listOf(
            PlannedStep(
                stepId = "fast_ok",
                actionType = ActionType.WEATHER_LOOKUP,
                label = "Weather",
                target = "Dhaka",
                isBackgroundParallel = true
            ),
            PlannedStep(
                stepId = "slow_step",
                actionType = ActionType.TOGGLE_TORCH,
                label = "Slow Torch",
                boolValue = true,
                timeoutMs = 150L,
                isBackgroundParallel = true
            ),
            PlannedStep(
                stepId = "dependent_skip",
                actionType = ActionType.WEB_SEARCH,
                label = "Search After Slow",
                target = "Aether",
                dependsOn = listOf("slow_step")
            ),
            // Cyclic pair should be broken without deadlock
            PlannedStep(
                stepId = "cyc_a",
                actionType = ActionType.SAVE_MEMORY,
                label = "Cycle A",
                textPayload = "A",
                dependsOn = listOf("cyc_b")
            ),
            PlannedStep(
                stepId = "cyc_b",
                actionType = ActionType.SAVE_MEMORY,
                label = "Cycle B",
                textPayload = "B",
                dependsOn = listOf("cyc_a")
            )
        )

        val nodes = executor.buildDag(steps)
        assertEquals(5, nodes.size)

        val statuses = mutableMapOf<String, com.example.ai.TaskStatus>()
        val summary = executor.executeDag(
            nodes = nodes,
            onTaskUpdate = { id, status, _, _, _ ->
                statuses[id] = status
            },
            onTorchStateChanged = {}
        ) { step ->
            logOrder.add(step.stepId)
            when (step.stepId) {
                "slow_step" -> {
                    kotlinx.coroutines.delay(500L)
                    com.example.system.ActionExecutionResult(true, "Done late")
                }
                else -> com.example.system.ActionExecutionResult(true, "OK")
            }
        }

        assertEquals(com.example.ai.TaskStatus.DONE, statuses["fast_ok"])
        assertEquals(com.example.ai.TaskStatus.TIMED_OUT, statuses["slow_step"])
        assertEquals(com.example.ai.TaskStatus.SKIPPED, statuses["dependent_skip"])
        assertEquals(com.example.ai.TaskStatus.DONE, statuses["cyc_a"])
        assertEquals(com.example.ai.TaskStatus.DONE, statuses["cyc_b"])
        assertFalse(logOrder.contains("dependent_skip"))
        assertEquals(1, summary.timedOutCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(3, summary.completedCount)
    }

    @Test
    fun `vault crypto manager generates deterministic passphrase and preferences persist hands-free and biometric flags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = com.example.data.local.VaultCryptoManager(context)
        val pass1 = crypto.getOrCreateDatabasePassphrase()
        val pass2 = crypto.getOrCreateDatabasePassphrase()
        assertEquals(64, pass1.size) // 32 bytes hex-encoded
        assertTrue(pass1.contentEquals(pass2))

        val vault = VaultRepository(context)
        vault.savePreferences(
            vault.loadPreferences().copy(
                biometricVaultLockEnabled = true,
                wakeWordEnabled = true,
                bargeInEnabled = true,
                customWakeWord = "Hey Jarvis"
            )
        )
        val reloaded = vault.loadPreferences()
        assertTrue(reloaded.biometricVaultLockEnabled)
        assertTrue(reloaded.wakeWordEnabled)
        assertTrue(reloaded.bargeInEnabled)
        assertEquals("Hey Jarvis", reloaded.customWakeWord)
    }

    @Test
    fun `wake word detector matches custom persona phrases and extracts inline commands`() {
        val inline = com.example.system.WakeWordDetector.match(
            transcript = "Hey Aether, turn on flashlight",
            customWakeWord = "Hey Aether",
            persona = AssistantPersona.AETHER
        )
        assertNotNull(inline)
        assertEquals("turn on flashlight", inline?.trailingCommand)

        val bangla = com.example.system.WakeWordDetector.match(
            transcript = "হে এথার আমাকে সময় বলো",
            persona = AssistantPersona.ARIA
        )
        assertNotNull(bangla)
        assertEquals("আমাকে সময় বলো", bangla?.trailingCommand)

        val bare = com.example.system.WakeWordDetector.match(
            transcript = "hey aria",
            persona = AssistantPersona.ARIA
        )
        assertNotNull(bare)
        assertEquals("", bare?.trailingCommand)

        val unrelated = com.example.system.WakeWordDetector.match("play some jazz music")
        assertEquals(null, unrelated)
    }

    @Test
    fun `vault locked card and streaming dag hud states render with interactive tags`() {
        var unlockClicks = 0
        val ampFlow = MutableStateFlow(0.25f)
        val streamingState = AssistantUiState(
            coreMode = CoreMode.THINKING,
            streamingReply = "Checking Dhaka weather and opening Maps...",
            activeAiModelLabel = "Gemini · 3.8 Flash",
            visionInsightLabel = "Center Focus (-42% px) · OCR 8 lines",
            isWakeWordArmed = true,
            activeTasks = listOf(
                com.example.ai.ActiveTaskItem(
                    id = "t1",
                    step = PlannedStep(stepId = "t1", actionType = ActionType.WEATHER_LOOKUP, label = "Weather"),
                    status = com.example.ai.TaskStatus.TIMED_OUT,
                    detail = "Timed out after 12s",
                    elapsedMs = 12000L,
                    timedOut = true
                ),
                com.example.ai.ActiveTaskItem(
                    id = "t2",
                    step = PlannedStep(stepId = "t2", actionType = ActionType.WEB_SEARCH, label = "Maps", dependsOn = listOf("t1")),
                    status = com.example.ai.TaskStatus.SKIPPED,
                    detail = "Skipped (t1 failed)",
                    dependsOn = listOf("t1")
                )
            )
        )

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                val scrollState = androidx.compose.foundation.rememberScrollState()
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(scrollState)
                ) {
                    TopTelemetryBar(
                        uiState = streamingState,
                        onCyclePersona = {},
                        onOpenPermissions = {},
                        onOpenVault = {}
                    )
                    CoreStatusAndReplyOverlay(
                        uiState = streamingState,
                        onAbortTasks = {},
                        onConfirmSensitive = {},
                        onDismissSensitive = {}
                    )
                    com.example.ui.components.VaultWorkspaceContent(
                        logs = emptyList(),
                        memories = emptyList(),
                        routines = emptyList(),
                        contacts = emptyList(),
                        onClose = null,
                        onReplayCommand = {},
                        onDeleteLog = {},
                        onClearLogs = {},
                        onAddMemory = {},
                        onTogglePinMemory = {},
                        onDeleteMemory = {},
                        onRunRoutine = {},
                        onSaveRoutine = { _, _, _ -> },
                        onDeleteRoutine = {},
                        onPickSystemContact = {},
                        onAddManualContact = { _, _, _, _ -> },
                        onDeleteContact = {},
                        isSqlCipherEncrypted = true,
                        encryptionBadgeLabel = "SQLCipher AES-256 · Keystore",
                        biometricLockEnabled = true,
                        isVaultUnlocked = false,
                        onRequestBiometricUnlock = { unlockClicks++ }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Checking Dhaka weather and opening Maps...").assertIsDisplayed()
        composeRule.onNodeWithText("Gemini · 3.8 Flash").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_locked_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vault_encryption_badge").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("biometric_unlock_button").performScrollTo().performClick()
        assertEquals(1, unlockClicks)
        assertNotNull(ampFlow.value)
    }

    @Test
    fun `echo guard policy enforces batch utterance lock, cooldown window, and route-aware barge-in`() {
        assertFalse(com.example.system.EchoGuardPolicy.shouldFinishUtteranceBatch("batch_1_part_0", "batch_1"))
        assertFalse(com.example.system.EchoGuardPolicy.shouldFinishUtteranceBatch("batch_1_pause_0", "batch_1"))
        assertTrue(com.example.system.EchoGuardPolicy.shouldFinishUtteranceBatch("batch_1", "batch_1"))

        assertTrue(com.example.system.EchoGuardPolicy.isEchoGuardActive(nowElapsedMs = 1000L, echoGuardUntilMs = 1650L))
        assertFalse(com.example.system.EchoGuardPolicy.isEchoGuardActive(nowElapsedMs = 1650L, echoGuardUntilMs = 1650L))

        assertFalse(
            com.example.system.EchoGuardPolicy.shouldRunBargeInMonitor(
                bargeInEnabled = true,
                bargeInMode = "TAP_ONLY",
                route = com.example.system.AudioOutputRoute.HEADSET_OR_BLE
            )
        )
        assertFalse(
            com.example.system.EchoGuardPolicy.shouldRunBargeInMonitor(
                bargeInEnabled = true,
                bargeInMode = "HEADSET_ONLY",
                route = com.example.system.AudioOutputRoute.LOUDSPEAKER
            )
        )
        assertTrue(
            com.example.system.EchoGuardPolicy.shouldRunBargeInMonitor(
                bargeInEnabled = true,
                bargeInMode = "HEADSET_ONLY",
                route = com.example.system.AudioOutputRoute.HEADSET_OR_BLE
            )
        )

        val (speakerThr, speakerFrames) = com.example.system.EchoGuardPolicy.computeBargeInThreshold(
            com.example.system.AudioOutputRoute.LOUDSPEAKER,
            baselineRms = 1200.0
        )
        assertTrue(speakerThr >= 3600.0)
        assertEquals(6, speakerFrames)

        val (headsetThr, headsetFrames) = com.example.system.EchoGuardPolicy.computeBargeInThreshold(
            com.example.system.AudioOutputRoute.HEADSET_OR_BLE,
            baselineRms = 500.0
        )
        assertTrue(headsetThr >= 1800.0)
        assertEquals(3, headsetFrames)
    }

    @Test
    fun `communication audio router and piper neural catalog stay within 25mb voice budget`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.system.CommunicationAudioRouter(context)
        val attrs = router.voiceCommunicationAttributes()
        assertEquals(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION, attrs.usage)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_SPEECH, attrs.contentType)

        val piper = com.example.voice.PiperNeuralVoiceManager(context)
        assertEquals(6, piper.catalog.size)
        piper.catalog.filter { it.downloadUrl.isNotBlank() }.forEach { pack ->
            assertTrue("Expected pack under 120 MB, got ${pack.downloadSizeMb}", pack.downloadSizeMb < 120f)
            assertTrue(pack.downloadUrl.startsWith("https://"))
        }
        assertEquals(6, piper.packStates.value.size)
    }

    @Test
    fun `screen macro manager coalesces typing, serializes steps, and compiles dag`() {
        com.example.automation.ScreenMacroManager.startRecording()
        com.example.automation.ScreenMacroManager.recordInteraction(
            com.example.automation.RecordedMacroStep(
                packageName = "com.example.shop",
                appLabel = "ShopApp",
                actionKind = "CLICK",
                label = "Search bar",
                timestampMs = 1000L
            )
        )
        com.example.automation.ScreenMacroManager.recordInteraction(
            com.example.automation.RecordedMacroStep(
                packageName = "com.example.shop",
                appLabel = "ShopApp",
                actionKind = "INPUT",
                label = "Search bar",
                textPayload = "lap",
                timestampMs = 1300L
            )
        )
        com.example.automation.ScreenMacroManager.recordInteraction(
            com.example.automation.RecordedMacroStep(
                packageName = "com.example.shop",
                appLabel = "ShopApp",
                actionKind = "INPUT",
                label = "Search bar",
                textPayload = "laptop",
                timestampMs = 1600L
            )
        )
        val captured = com.example.automation.ScreenMacroManager.stopAndDrainSteps()
        assertEquals(2, captured.size)
        assertEquals("laptop", captured[1].textPayload)

        val json = com.example.automation.ScreenMacroManager.encodeStepsJson(captured)
        val decoded = com.example.automation.ScreenMacroManager.decodeStepsJson(json)
        assertEquals(captured.size, decoded.size)

        val dag = com.example.automation.ScreenMacroManager.compileMacroToDagSteps(decoded)
        assertEquals(3, dag.size)
        assertEquals(ActionType.LAUNCH_APP, dag[0].actionType)
        assertEquals(ActionType.UI_TAP_TEXT, dag[1].actionType)
        assertEquals(ActionType.UI_INPUT_TEXT, dag[2].actionType)
        assertEquals(listOf(dag[0].stepId), dag[1].dependsOn)
        assertEquals(listOf(dag[1].stepId), dag[2].dependsOn)
    }

    @Test
    fun `vault v2 persists reminders macros and voice studio preferences`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = VaultRepository(context)

        val remId = vault.saveReminder(
            com.example.data.local.ReminderEntity(
                title = "Call team lead",
                triggerAtMillis = System.currentTimeMillis() + 600_000L
            )
        )
        assertTrue(vault.getPendingReminders().any { it.id == remId && it.title == "Call team lead" })
        vault.setReminderCompleted(remId, true)
        assertFalse(vault.getPendingReminders().any { it.id == remId })

        val macroId = vault.saveMacro(
            com.example.data.local.ScreenMacroEntity(
                name = "Morning News",
                voiceTrigger = "morning news",
                stepsJson = "[]",
                stepCount = 3
            )
        )
        vault.recordMacroRun(macroId)
        val loadedMacro = vault.getMacrosSnapshot().firstOrNull { it.id == macroId }
        assertNotNull(loadedMacro)
        assertEquals(1, loadedMacro?.runCount)

        vault.savePreferences(
            vault.loadPreferences().copy(
                voiceEngineMode = "PIPER",
                bargeInMode = "HEADSET_ONLY",
                dailyBriefingEnabled = true,
                briefingHour = 7,
                briefingMinute = 30
            )
        )
        val prefs = vault.loadPreferences()
        assertEquals("PIPER", prefs.voiceEngineMode)
        assertEquals("HEADSET_ONLY", prefs.bargeInMode)
        assertTrue(prefs.dailyBriefingEnabled)
        assertEquals(7, prefs.briefingHour)
        assertEquals(30, prefs.briefingMinute)
    }

    @Test
    fun `voice studio sheet renders and switches engine interactively`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val piper = com.example.voice.PiperNeuralVoiceManager(context)
        val state = AssistantUiState(
            voicePacks = piper.packStates.value
        )
        var engineSelected = ""

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                com.example.ui.components.VoiceStudioContent(
                    uiState = state,
                    onClose = null,
                    onSelectEngineMode = { engineSelected = it },
                    onSelectBargeInMode = {},
                    onDownloadPack = {},
                    onSelectPack = {},
                    onDeletePack = {},
                    onTestSample = {},
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .height(460.dp)
                )
            }
        }

        composeRule.onNodeWithText("NEURAL VOICE STUDIO").assertIsDisplayed()
        composeRule.onNodeWithText("Piper Neural").performScrollTo().performClick()
        assertEquals("PIPER", engineSelected)
    }

    @Test
    fun `vault briefing and macros tabs render and trigger callbacks`() {
        var speakBriefingClicked = 0

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                com.example.ui.components.VaultWorkspaceContent(
                    logs = emptyList(),
                    memories = emptyList(),
                    routines = emptyList(),
                    contacts = emptyList(),
                    onClose = null,
                    onReplayCommand = {},
                    onDeleteLog = {},
                    onClearLogs = {},
                    onAddMemory = {},
                    onTogglePinMemory = {},
                    onDeleteMemory = {},
                    onRunRoutine = {},
                    onSaveRoutine = { _, _, _ -> },
                    onDeleteRoutine = {},
                    onPickSystemContact = {},
                    onAddManualContact = { _, _, _, _ -> },
                    onDeleteContact = {},
                    isMacroRecording = true,
                    macroStepCount = 2,
                    onSpeakBriefingNow = { speakBriefingClicked++ },
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .height(460.dp)
                )
            }
        }

        composeRule.onNodeWithTag("vault_tab_briefing").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("speak_briefing_now_button").performClick()
        assertEquals(1, speakBriefingClicked)

        composeRule.onNodeWithTag("vault_tab_macros").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("toggle_macro_recording_button").assertIsDisplayed()
    }

    @Test
    fun `voice isolation dsp attenuates sub-bass rumble and segments clear human voice`() {
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

        // Feed 15 frames of 60 Hz sub-bass / speaker rumble -> should be strongly attenuated and NOT trigger speech
        val subBassFrame = ShortArray(320) { i ->
            (6000.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 60.0 * i / 16_000.0)).toInt().toShort()
        }
        var lastRumbleRes: com.example.audio.DspFrameResult? = null
        repeat(15) {
            val res = dsp.processFrame(subBassFrame)
            lastRumbleRes = res
            assertEquals(null, dsp.feedForUtterance(res))
        }
        assertTrue("Expected sub-bass rumble to be attenuated", (lastRumbleRes?.filteredRms ?: 9999f) < 1200f)
        assertFalse("Sub-bass rumble must not classify as speech", lastRumbleRes?.isSpeechFrame == true)

        // Feed 35 frames (700 ms) of 650 Hz human voice-band tone followed by 50 frames (1000 ms) of quiet
        val voiceFrame = ShortArray(320) { i ->
            (4500.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 650.0 * i / 16_000.0)).toInt().toShort()
        }
        val silenceFrame = ShortArray(320)
        var detectedSpeechFrames = 0
        repeat(35) {
            val res = dsp.processFrame(voiceFrame)
            if (res.isSpeechFrame) detectedSpeechFrames++
            dsp.feedForUtterance(res)
        }
        assertTrue("Expected voice-band tone to trigger speech frames", detectedSpeechFrames >= 10)

        var emittedUtterance: com.example.audio.CompletedUtterancePcm? = null
        repeat(50) {
            val res = dsp.processFrame(silenceFrame)
            val seg = dsp.feedForUtterance(res)
            if (seg != null) emittedUtterance = seg
        }
        assertNotNull("Expected VAD to emit completed filtered speech utterance", emittedUtterance)
        assertTrue((emittedUtterance?.durationMs ?: 0L) >= 450L)
    }

    @Test
    fun `audio wav encoder produces valid 16khz mono pcm header`() {
        val samples = shortArrayOf(100, -200, 300, -400)
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
    fun `filtered microphone engine handles missing permission and voice studio renders filter card`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.system.CommunicationAudioRouter(context)
        val engine = com.example.audio.FilteredMicrophoneEngine(context, router)

        var capturedErr: String? = null
        engine.startCapture(
            continuousMode = false,
            isEchoGuardSuppressed = { false },
            onAmplitude = {},
            onSpeechSegmentReady = {},
            onCaptureError = { capturedErr = it }
        )
        assertFalse(engine.isCapturing)
        assertNotNull(capturedErr)
        engine.release()

        var toggled = 0
        val diagState = AssistantUiState(
            audioFilterDiagnostics = com.example.audio.AudioFilterDiagnostics(
                isCapturing = true,
                effects = com.example.audio.AudioEffectCapabilities(
                    aecState = com.example.audio.AudioEffectState.ACTIVE,
                    nsState = com.example.audio.AudioEffectState.UNSUPPORTED,
                    agcState = com.example.audio.AudioEffectState.ACTIVE
                ),
                noiseFloorDb = -48f,
                voiceConfidence = 0.84f,
                isSpeechDetected = true
            )
        )
        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                com.example.ui.components.VoiceStudioContent(
                    uiState = diagState,
                    onClose = null,
                    onSelectEngineMode = {},
                    onSelectBargeInMode = {},
                    onToggleFilteredMic = { toggled++ },
                    onDownloadPack = {},
                    onSelectPack = {},
                    onDeletePack = {},
                    onTestSample = {},
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .height(480.dp)
                )
            }
        }

        composeRule.onNodeWithTag("audio_filter_diagnostics_card").assertIsDisplayed()
        composeRule.onNodeWithText("Filtered Mic · AudioRecord (VOICE_COMMUNICATION)").assertIsDisplayed()
        composeRule.onNodeWithTag("filtered_mic_switch").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `weather pipeline localizes bangla forecast, uses 16s timeout, and replaces stale searching fallback`() {
        val repo = FreeWebToolsRepository()
        val sampleForecast = """
            {
              "current": {
                "temperature_2m": 29.4,
                "apparent_temperature": 33.0,
                "relative_humidity_2m": 78,
                "wind_speed_10m": 11.5,
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
        val banglaForecast = repo.formatOpenMeteoForecast("Dhaka", sampleForecast, preferBangla = true)
        assertTrue(banglaForecast.contains("Dhaka"))
        assertTrue(banglaForecast.contains("29°C"))
        assertTrue(banglaForecast.contains("বৃষ্টির সম্ভাবনা 80%"))

        val dagExecutor = com.example.core.TaskDagExecutor()
        val weatherStep = PlannedStep(
            stepId = "w1",
            actionType = ActionType.WEATHER_LOOKUP,
            label = "Weather Lookup",
            target = "current"
        )
        assertEquals(16_000L, dagExecutor.resolveTimeoutMs(weatherStep))
        val dagNodes = dagExecutor.buildDag(listOf(weatherStep))

        val fallback = com.example.core.AgentOrchestrator.resolvePipelineFallbackReply(
            initialReply = "আবহাওয়া খুঁজে দিচ্ছি...",
            dagNodes = dagNodes,
            timedOutCount = 1,
            failedCount = 0,
            preferBangla = true,
            webTools = repo
        )
        assertFalse(fallback.contains("খুঁজে দিচ্ছি"))
        assertTrue(fallback.contains("আবহাওয়া") || fallback.contains("আবহাওয়া") || fallback.contains("দুঃখিত"))
    }

    @Test
    fun `wake trigger mode policy enforces button-only idle zero mic and persists preference`() {
        assertEquals(
            com.example.system.EchoGuardPolicy.StandbyDecision.BUTTON_ONLY_IDLE,
            com.example.system.EchoGuardPolicy.resolveBackgroundStandby(
                wakeWordEnabled = true,
                micGranted = true,
                backgroundServicePaused = false,
                filteredMicCapturing = false,
                pauseWakeDuringMusic = true,
                musicPlaybackActive = false,
                isCoreIdle = true,
                wakeTriggerMode = com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY
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
                isCoreIdle = true,
                wakeTriggerMode = com.example.data.local.WakeTriggerMode.HYBRID_KWS_AND_BUTTON
            )
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = VaultRepository(context)
        vault.savePreferences(
            vault.loadPreferences().copy(
                wakeTriggerMode = com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY,
                wakeWordEnabled = false
            )
        )
        val reloaded = vault.loadPreferences()
        assertEquals(com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY, reloaded.wakeTriggerMode)
        assertFalse(reloaded.wakeWordEnabled)
    }

    @Test
    fun `multi-step classifier separates multi-hop youtube workflow and parses subGoals`() {
        val multiHopPrompt = "YouTube a novaking entertainment search kore channel a giye first video play koro"
        assertTrue(com.example.core.MultiStepGoalClassifier.isMultiHopYouTubeWorkflow(multiHopPrompt))

        val simplePlay = "play Despacito on YouTube"
        assertFalse(com.example.core.MultiStepGoalClassifier.isMultiHopYouTubeWorkflow(simplePlay))

        val json = """
            {
              "reply": "Opening YouTube to open Novaking Entertainment's first video.",
              "needsScreenObservation": true,
              "continueLoopAfterAction": true,
              "subGoals": [
                "Open YouTube",
                "Search Novaking Entertainment",
                "Open channel & Videos tab",
                "Play first video"
              ],
              "steps": [
                {
                  "actionType": "LAUNCH_APP",
                  "label": "Open YouTube",
                  "target": "YouTube"
                }
              ]
            }
        """.trimIndent()
        val plan = GeminiCommandService().parseCommandPlan(json)
        assertEquals(4, plan.subGoals.size)
        assertEquals("Open channel & Videos tab", plan.subGoals[2])
        assertTrue(com.example.core.MultiStepGoalClassifier.isMultiHopAppWorkflow(multiHopPrompt, plan))
    }

    @Test
    fun `react multi-step progress hud renders waiting indicator and vision chip`() {
        val reActState = AssistantUiState(
            coreMode = CoreMode.OBSERVING_SCREEN,
            reActSubGoals = listOf("Open YouTube", "Search query", "Open Videos", "Play first video"),
            reActActiveSubGoalIndex = 1,
            reActWaitingForLoad = true,
            screenVisionSourceLabel = "ScreenVision + OCR",
            latestReply = "Opening Novaking Entertainment channel..."
        )

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                CoreStatusAndReplyOverlay(
                    uiState = reActState,
                    onAbortTasks = {},
                    onConfirmSensitive = {},
                    onDismissSensitive = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("react_progress_card").assertIsDisplayed()
        composeRule.onNodeWithTag("react_waiting_indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_vision_source_chip").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for screen to load...").assertIsDisplayed()
    }

    @Test
    fun `settings hub wake mode selector and kws download card render interactively`() {
        val settingsState = AssistantUiState(
            coreMode = CoreMode.IDLE
        )
        var pickedMode: com.example.data.local.WakeTriggerMode? = null
        var kwsClicked = 0

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                com.example.ui.components.SettingsHubContent(
                    uiState = settingsState,
                    onDismiss = {},
                    onSelectPersona = {},
                    onToggleWakeWord = {},
                    onUpdateWakeWordPhrase = {},
                    onTogglePauseWakeDuringMusic = {},
                    onToggleBargeIn = {},
                    onToggleContinuousLoop = {},
                    onToggleAutonomousMode = {},
                    onSelectLocale = {},
                    onOpenVoiceStudio = {},
                    onOpenApiSettings = {},
                    onOpenPermissions = {},
                    onSelectWakeTriggerMode = { pickedMode = it },
                    onDownloadKwsPack = { kwsClicked++ },
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .height(900.dp)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_hub_sheet")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("kws_pack_download_button"))
        composeRule.onNodeWithTag("kws_pack_download_button").performClick()
        assertEquals(1, kwsClicked)

        composeRule.onNodeWithTag("settings_hub_sheet")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("wake_mode_assistant_button_only"))
        composeRule.onNodeWithTag("wake_mode_assistant_button_only").performClick()
        assertEquals(com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY, pickedMode)
    }

    @Test
    fun `vault persists offline vision token mode and cumulative token savings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = VaultRepository(context)
        vault.savePreferences(
            vault.loadPreferences().copy(
                visionTokenMode = com.example.data.local.VisionTokenMode.HYBRID_COMPACT,
                estimatedTokensSavedTotal = 4250L
            )
        )
        val reloaded = vault.loadPreferences()
        assertEquals(com.example.data.local.VisionTokenMode.HYBRID_COMPACT, reloaded.visionTokenMode)
        assertEquals(4250L, reloaded.estimatedTokensSavedTotal)
    }

    @Test
    fun `hud chrome and settings hub display offline vision mode and token savings telemetry`() {
        val prefs = com.example.data.local.AssistantPreferences(
            visionTokenMode = com.example.data.local.VisionTokenMode.OFFLINE_FIRST,
            estimatedTokensSavedTotal = 3100L
        )
        val hudState = AssistantUiState(
            preferences = prefs,
            coreMode = CoreMode.OBSERVING_SCREEN,
            screenVisionSourceLabel = "OFFLINE (<50MB)",
            estimatedTokensSavedSession = 1240L
        )
        var selectedMode: com.example.data.local.VisionTokenMode? = null

        composeRule.setContent {
            Aether3DTheme(persona = AssistantPersona.AETHER) {
                androidx.compose.foundation.layout.Column {
                    CoreStatusAndReplyOverlay(
                        uiState = hudState,
                        onAbortTasks = {},
                        onConfirmSensitive = {},
                        onDismissSensitive = {}
                    )
                    com.example.ui.components.SettingsHubContent(
                        uiState = hudState,
                        onDismiss = {},
                        onSelectPersona = {},
                        onToggleWakeWord = {},
                        onUpdateWakeWordPhrase = {},
                        onTogglePauseWakeDuringMusic = {},
                        onToggleBargeIn = {},
                        onToggleContinuousLoop = {},
                        onToggleAutonomousMode = {},
                        onSelectLocale = {},
                        onOpenVoiceStudio = {},
                        onOpenApiSettings = {},
                        onOpenPermissions = {},
                        onSelectVisionTokenMode = { selectedMode = it },
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .height(600.dp)
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tokens_saved_chip").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_vision_source_chip").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_hub_sheet")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("vision_mode_hybrid_compact"))
        composeRule.onNodeWithTag("settings_tokens_saved_label").assertIsDisplayed()
        composeRule.onNodeWithTag("vision_mode_hybrid_compact").performClick()
        assertEquals(com.example.data.local.VisionTokenMode.HYBRID_COMPACT, selectedMode)
    }
}
