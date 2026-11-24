package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.BiometricVaultHelper
import com.example.ui.components.CoreStatusAndReplyOverlay
import com.example.ui.components.NvidiaApiDialog
import com.example.ui.components.PermissionsWizardSheet
import com.example.ui.components.SettingsHubSheet
import com.example.ui.components.TacticalCornerReticles
import com.example.ui.components.TopTelemetryBar
import com.example.ui.components.VaultBottomSheet
import com.example.ui.components.VaultWorkspaceContent
import com.example.ui.components.VoiceDock
import com.example.ui.components.VoiceStudioBottomSheet
import com.example.ui.gl.Aether3DViewport
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.VoidObsidian
import com.example.ui.viewmodel.JarvisViewModel
import kotlinx.coroutines.delay

@Composable
fun AetherCockpitScreen(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.historyLogs.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val contacts by viewModel.quickContacts.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val macros by viewModel.macros.collectAsStateWithLifecycle()

    var showPermissionsWizard by remember { mutableStateOf(false) }
    var showVaultSheet by remember { mutableStateOf(false) }
    var showNvidiaApiDialog by remember { mutableStateOf(false) }
    var showVoiceStudio by remember { mutableStateOf(false) }
    var showSettingsHub by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    val triggerBiometricUnlock = remember(activity) {
        {
            if (activity != null) {
                BiometricVaultHelper.authenticateVault(
                    activity = activity,
                    onSuccess = {
                        biometricError = null
                        viewModel.unlockVault()
                    },
                    onError = { msg ->
                        biometricError = msg
                    }
                )
            } else {
                biometricError = "Biometric prompt requires an active FragmentActivity."
            }
        }
    }

    // Open Permissions Wizard after initial frame settles to avoid colliding with window SurfaceSyncGroup
    LaunchedEffect(Unit) {
        if (!uiState.preferences.hasCompletedOnboardingPrompt && !uiState.permissionSnapshot.allEnabled) {
            delay(350L)
            showPermissionsWizard = true
            viewModel.markOnboardingSeen()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissionsAndTelemetry()
        if (uiState.backgroundServiceActive) {
            runCatching { com.example.service.VoiceAgentService.start(context) }
        }
    }

    // Re-lock vault only on ON_STOP (never ON_PAUSE, since BiometricPrompt overlay pauses the Activity)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (uiState.preferences.biometricVaultLockEnabled) {
            viewModel.lockVault()
        }
    }

    val batchPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionsAndTelemetry()
    }

    val wavSamplePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importAetherVoiceSample(uri)
        }
    }

    val onnxModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCustomOnnxModel(uri)
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) {
            viewModel.importPickedContact(uri)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            com.example.service.ScreenVisionService.startWithConsent(
                context = context,
                resultCode = result.resultCode,
                data = data
            )
            viewModel.refreshPermissionsAndTelemetry()
        }
    }

    val defaultAssistantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissionsAndTelemetry()
    }

    val triggerDeepScreenVisionToggle: () -> Unit = {
        if (uiState.isDeepVisionActive) {
            viewModel.stopDeepScreenVision()
        } else {
            val mgr = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as? android.media.projection.MediaProjectionManager
            if (mgr != null) {
                mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }
    }

    val triggerDefaultAssistantRequest: () -> Unit = {
        runCatching {
            defaultAssistantLauncher.launch(
                viewModel.permissionsManager.buildRequestDefaultAssistantIntent()
            )
        }
    }

    BackHandler(enabled = showSettingsHub || showPermissionsWizard || showVaultSheet || showNvidiaApiDialog || showVoiceStudio) {
        when {
            showVoiceStudio -> showVoiceStudio = false
            showNvidiaApiDialog -> showNvidiaApiDialog = false
            showPermissionsWizard -> showPermissionsWizard = false
            showSettingsHub -> showSettingsHub = false
            showVaultSheet -> showVaultSheet = false
        }
    }

    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidObsidian)
    ) {
        val isExpandedTablet = maxWidth >= 840.dp

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Layer 1: Full-Bleed Hardware OpenGL ES 3D Core (zero recomposition flow)
                Aether3DViewport(
                    coreMode = uiState.coreMode,
                    persona = uiState.preferences.persona,
                    audioAmplitudeFlow = viewModel.audioAmplitude,
                    onCoreTapped = {
                        if (!uiState.permissionSnapshot.micGranted) {
                            batchPermissionLauncher.launch(
                                viewModel.permissionsManager.runtimePermissionsToRequest()
                            )
                        } else {
                            viewModel.onMicTapped()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Layer 2: Tactical Corner Reticles
                TacticalCornerReticles(accentColor = accent)

                // Layer 3: Safe-Area Compose HUD Chrome
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    TopTelemetryBar(
                        uiState = uiState,
                        onCyclePersona = { viewModel.cyclePersona() },
                        onOpenPermissions = { showPermissionsWizard = true },
                        onOpenVault = {
                            val opening = !showVaultSheet
                            showVaultSheet = opening
                            if (opening && uiState.preferences.biometricVaultLockEnabled && !uiState.isVaultUnlocked) {
                                biometricError = null
                                triggerBiometricUnlock()
                            }
                        },
                        onOpenApiSettings = { showNvidiaApiDialog = true },
                        onOpenVoiceStudio = { showVoiceStudio = true }
                    )

                    Spacer(Modifier.weight(1f))

                    CoreStatusAndReplyOverlay(
                        uiState = uiState,
                        onAbortTasks = { viewModel.abortAllTasks() },
                        onConfirmSensitive = { viewModel.confirmSensitiveSteps() },
                        onDismissSensitive = { viewModel.dismissSensitiveSteps() },
                        onOpenApiSettings = { showNvidiaApiDialog = true }
                    )

                    Spacer(Modifier.height(6.dp))

                    VoiceDock(
                        uiState = uiState,
                        audioAmplitudeFlow = viewModel.audioAmplitude,
                        onMicClick = {
                            if (!uiState.permissionSnapshot.micGranted) {
                                batchPermissionLauncher.launch(
                                    viewModel.permissionsManager.runtimePermissionsToRequest()
                                )
                            } else {
                                viewModel.onMicTapped()
                            }
                        },
                        onSubmitText = { viewModel.submitTextCommand(it) },
                        onToggleBackgroundService = {
                            if (!uiState.permissionSnapshot.micGranted || !uiState.permissionSnapshot.notificationsGranted) {
                                showPermissionsWizard = true
                            } else {
                                viewModel.toggleBackgroundService()
                            }
                        },
                        onToggleTts = { viewModel.toggleTts() },
                        onOpenSettings = { showSettingsHub = true },
                        onToggleLiveDuplex = {
                            if (!uiState.permissionSnapshot.micGranted) {
                                batchPermissionLauncher.launch(
                                    viewModel.permissionsManager.runtimePermissionsToRequest()
                                )
                            } else {
                                viewModel.toggleLiveDuplexMode()
                            }
                        }
                    )
                }
            }

            if (isExpandedTablet && showVaultSheet) {
                Surface(
                    color = DeepCockpit,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    VaultWorkspaceContent(
                        logs = logs,
                        memories = memories,
                        routines = routines,
                        contacts = contacts,
                        onClose = { showVaultSheet = false },
                        onReplayCommand = { viewModel.submitTextCommand(it) },
                        onDeleteLog = { viewModel.deleteLog(it) },
                        onClearLogs = { viewModel.clearLogs() },
                        onAddMemory = { viewModel.addMemory(it) },
                        onTogglePinMemory = { viewModel.toggleMemoryPinned(it) },
                        onDeleteMemory = { viewModel.deleteMemory(it) },
                        onRunRoutine = { viewModel.runRoutine(it) },
                        onSaveRoutine = { t, d, p -> viewModel.saveRoutine(t, d, p) },
                        onDeleteRoutine = { viewModel.deleteRoutine(it) },
                        onPickSystemContact = { contactPickerLauncher.launch(null) },
                        onAddManualContact = { a, d, p, e -> viewModel.addQuickContact(a, d, p, e) },
                        onDeleteContact = { viewModel.deleteQuickContact(it) },
                        isSqlCipherEncrypted = uiState.isSqlCipherEncrypted,
                        encryptionBadgeLabel = uiState.encryptionBadgeLabel,
                        biometricLockEnabled = uiState.preferences.biometricVaultLockEnabled,
                        isVaultUnlocked = uiState.isVaultUnlocked,
                        biometricError = biometricError,
                        onRequestBiometricUnlock = {
                            biometricError = null
                            triggerBiometricUnlock()
                        },
                        onToggleBiometricLock = {
                            biometricError = null
                            viewModel.toggleBiometricVaultLock()
                        },
                        onLockVaultNow = { viewModel.lockVault() },
                        reminders = reminders,
                        dailyBriefingEnabled = uiState.preferences.dailyBriefingEnabled,
                        briefingHour = uiState.preferences.briefingHour,
                        briefingMinute = uiState.preferences.briefingMinute,
                        onToggleDailyBriefing = { en, h, m -> viewModel.toggleDailyBriefing(en, h, m) },
                        onSpeakBriefingNow = { viewModel.runDailyBriefing() },
                        onAddReminder = { title, mins -> viewModel.addReminder(title, mins) },
                        onToggleReminderCompleted = { id, done -> viewModel.toggleReminderCompleted(id, done) },
                        onDeleteReminder = { id -> viewModel.deleteReminder(id) },
                        macros = macros,
                        isMacroRecording = uiState.isMacroRecording,
                        macroStepCount = uiState.macroStepCount,
                        onToggleMacroRecording = { name -> viewModel.toggleMacroRecording(name) },
                        onReplayMacro = { macro -> viewModel.replayMacro(macro) },
                        onDeleteMacro = { id -> viewModel.deleteMacro(id) }
                    )
                }
            }
        }

        if (!isExpandedTablet && showVaultSheet) {
            VaultBottomSheet(
                logs = logs,
                memories = memories,
                routines = routines,
                contacts = contacts,
                onDismiss = { showVaultSheet = false },
                onReplayCommand = { viewModel.submitTextCommand(it) },
                onDeleteLog = { viewModel.deleteLog(it) },
                onClearLogs = { viewModel.clearLogs() },
                onAddMemory = { viewModel.addMemory(it) },
                onTogglePinMemory = { viewModel.toggleMemoryPinned(it) },
                onDeleteMemory = { viewModel.deleteMemory(it) },
                onRunRoutine = { viewModel.runRoutine(it) },
                onSaveRoutine = { t, d, p -> viewModel.saveRoutine(t, d, p) },
                onDeleteRoutine = { viewModel.deleteRoutine(it) },
                onPickSystemContact = { contactPickerLauncher.launch(null) },
                onAddManualContact = { a, d, p, e -> viewModel.addQuickContact(a, d, p, e) },
                onDeleteContact = { viewModel.deleteQuickContact(it) },
                isSqlCipherEncrypted = uiState.isSqlCipherEncrypted,
                encryptionBadgeLabel = uiState.encryptionBadgeLabel,
                biometricLockEnabled = uiState.preferences.biometricVaultLockEnabled,
                isVaultUnlocked = uiState.isVaultUnlocked,
                biometricError = biometricError,
                onRequestBiometricUnlock = {
                    biometricError = null
                    triggerBiometricUnlock()
                },
                onToggleBiometricLock = {
                    biometricError = null
                    viewModel.toggleBiometricVaultLock()
                },
                onLockVaultNow = { viewModel.lockVault() },
                reminders = reminders,
                dailyBriefingEnabled = uiState.preferences.dailyBriefingEnabled,
                briefingHour = uiState.preferences.briefingHour,
                briefingMinute = uiState.preferences.briefingMinute,
                onToggleDailyBriefing = { en, h, m -> viewModel.toggleDailyBriefing(en, h, m) },
                onSpeakBriefingNow = { viewModel.runDailyBriefing() },
                onAddReminder = { title, mins -> viewModel.addReminder(title, mins) },
                onToggleReminderCompleted = { id, done -> viewModel.toggleReminderCompleted(id, done) },
                onDeleteReminder = { id -> viewModel.deleteReminder(id) },
                macros = macros,
                isMacroRecording = uiState.isMacroRecording,
                macroStepCount = uiState.macroStepCount,
                onToggleMacroRecording = { name -> viewModel.toggleMacroRecording(name) },
                onReplayMacro = { macro -> viewModel.replayMacro(macro) },
                onDeleteMacro = { id -> viewModel.deleteMacro(id) }
            )
        }

        if (showPermissionsWizard) {
            PermissionsWizardSheet(
                uiState = uiState,
                onDismiss = { showPermissionsWizard = false },
                onRequestAllRuntimePermissions = {
                    batchPermissionLauncher.launch(
                        viewModel.permissionsManager.runtimePermissionsToRequest()
                    )
                },
                onLaunchSpecialSettings = { permId ->
                    runCatching {
                        context.startActivity(
                            viewModel.permissionsManager.buildSpecialSettingsIntent(permId)
                        )
                    }
                },
                onToggleAutonomousMode = { viewModel.toggleAutonomousMode() },
                onToggleContinuousLoop = { viewModel.toggleContinuousLoop() },
                onToggleSplitScreen = { viewModel.toggleSplitScreenLaunch() },
                onToggleWakeWord = { viewModel.toggleWakeWord() },
                onToggleBargeIn = { viewModel.toggleBargeIn() },
                onToggleBiometricVaultLock = {
                    biometricError = null
                    viewModel.toggleBiometricVaultLock()
                },
                onSelectLocale = { viewModel.setSpeechLocale(it) },
                onOpenNvidiaApiSettings = {
                    showPermissionsWizard = false
                    showNvidiaApiDialog = true
                },
                onOpenVoiceStudio = {
                    showPermissionsWizard = false
                    showVoiceStudio = true
                },
                onToggleDeepScreenVision = triggerDeepScreenVisionToggle,
                onRequestDefaultAssistant = triggerDefaultAssistantRequest
            )
        }

        if (showSettingsHub) {
            SettingsHubSheet(
                uiState = uiState,
                onDismiss = { showSettingsHub = false },
                onSelectPersona = { viewModel.selectPersona(it) },
                onToggleWakeWord = { viewModel.toggleWakeWord() },
                onUpdateWakeWordPhrase = { viewModel.updateWakeWordPhrase(it) },
                onTogglePauseWakeDuringMusic = { viewModel.togglePauseWakeDuringMusic() },
                onToggleBargeIn = { viewModel.toggleBargeIn() },
                onToggleContinuousLoop = { viewModel.toggleContinuousLoop() },
                onToggleAutonomousMode = { viewModel.toggleAutonomousMode() },
                onSelectLocale = { viewModel.setSpeechLocale(it) },
                onOpenVoiceStudio = { showVoiceStudio = true },
                onOpenApiSettings = { showNvidiaApiDialog = true },
                onOpenPermissions = { showPermissionsWizard = true },
                onSelectWakeTriggerMode = { viewModel.setWakeTriggerMode(it) },
                onDownloadKwsPack = { viewModel.downloadKwsPack() },
                onToggleDeepScreenVision = triggerDeepScreenVisionToggle,
                onRequestDefaultAssistant = triggerDefaultAssistantRequest,
                onSelectVisionTokenMode = { viewModel.setVisionTokenMode(it) }
            )
        }

        if (showVoiceStudio) {
            VoiceStudioBottomSheet(
                uiState = uiState,
                onDismiss = { showVoiceStudio = false },
                onSelectEngineMode = { viewModel.setVoiceEngineMode(it) },
                onSelectBargeInMode = { viewModel.setBargeInMode(it) },
                onToggleFilteredMic = { viewModel.toggleFilteredMicMode() },
                onDownloadPack = { viewModel.downloadVoicePack(it) },
                onSelectPackForPersona = { packId, persona ->
                    viewModel.selectVoicePack(packId, persona)
                },
                onSelectPack = { viewModel.selectVoicePack(it) },
                onDeletePack = { viewModel.deleteVoicePack(it) },
                onTestSample = { viewModel.testVoiceSample(it) },
                onSetVoiceSpeed = { viewModel.setVoiceSpeed(it) },
                onImportVoiceSampleClick = {
                    wavSamplePickerLauncher.launch(arrayOf("audio/*"))
                },
                onPreviewImportedSampleClick = {
                    viewModel.previewImportedAetherSample()
                },
                onImportCustomOnnxClick = {
                    onnxModelPickerLauncher.launch(arrayOf("*/*"))
                }
            )
        }

        if (showNvidiaApiDialog) {
            NvidiaApiDialog(
                preferences = uiState.preferences,
                isConfigured = viewModel.isNvidiaConfigured(),
                onDismiss = { showNvidiaApiDialog = false },
                onSave = { nvidiaKey, nvidiaModel, preferPrimary, geminiKey, geminiModel ->
                    viewModel.saveApiSettings(nvidiaKey, nvidiaModel, preferPrimary, geminiKey, geminiModel)
                },
                onFetchModels = { overrideKey ->
                    viewModel.fetchLiveNvidiaModels(overrideKey)
                }
            )
        }
    }
}
