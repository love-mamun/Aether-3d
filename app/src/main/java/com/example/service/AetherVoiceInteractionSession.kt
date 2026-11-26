package com.example.service

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.ai.CoreMode
import com.example.ai.TaskStatus
import com.example.core.AgentOrchestrator
import com.example.core.AssistantUiState
import com.example.ui.theme.Aether3DTheme
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald

/**
 * Floating translucent assistant overlay sheet displayed directly above any active app
 * when Aether is invoked via Android's Default Digital Assistant gesture / power hold.
 */
class AetherVoiceInteractionSession(
    context: Context
) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private var capturedAssistHint: String = ""

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        val orchestrator = AgentOrchestrator.getInstance(context)
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewTreeLifecycleOwner(this@AetherVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@AetherVoiceInteractionSession)
        }

        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by orchestrator.uiState.collectAsState()
                Aether3DTheme(persona = state.preferences.persona) {
                    AetherAssistantOverlaySheet(
                        state = state,
                        assistScreenHint = capturedAssistHint,
                        onMicTap = {
                            if (state.coreMode == CoreMode.LISTENING) {
                                orchestrator.stopListening()
                            } else {
                                orchestrator.startListening("Listening over app...")
                            }
                        },
                        onSubmitText = { cmd ->
                            val clean = cmd.trim()
                            if (clean.isNotBlank()) {
                                orchestrator.submitCommand(
                                    prompt = clean,
                                    sourceMode = "SYSTEM_ASSIST_SHEET"
                                )
                            }
                        },
                        onAbortTap = { orchestrator.abortAllTasks() },
                        onOpenFullHud = {
                            runCatching {
                                startAssistantActivity(
                                    Intent(context, MainActivity::class.java).addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                )
                            }
                            hide()
                        },
                        onCloseSheet = { hide() }
                    )
                }
            }
        }
        container.addView(composeView)
        return container
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val structure = state.assistStructure
        capturedAssistHint = extractAssistSummary(structure)
        val pkg = structure?.activityComponent?.packageName.orEmpty()
        if (pkg.isNotBlank() && pkg != context.packageName) {
            AgentOrchestrator.getInstance(context).updateSystemAssistContext(
                packageName = pkg,
                extractedText = capturedAssistHint
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching {
            if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            if (lifecycleRegistry.currentState < Lifecycle.State.RESUMED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }
        // Only open mic if the user triggered gesture cleanly and holds permission; zero standby when idle
        val orchestrator = AgentOrchestrator.getInstance(context)
        if (orchestrator.uiState.value.permissionSnapshot.micGranted &&
            orchestrator.uiState.value.coreMode == CoreMode.IDLE
        ) {
            orchestrator.startListening("Ask Aether...")
        }
    }

    override fun onHide() {
        runCatching {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }
        super.onHide()
    }

    override fun onDestroy() {
        runCatching {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.onDestroy()
    }

    private fun extractAssistSummary(structure: AssistStructure?): String {
        if (structure == null || structure.windowNodeCount <= 0) return ""
        val parts = mutableListOf<String>()
        fun traverse(node: AssistStructure.ViewNode?) {
            if (node == null || parts.size >= 14) return
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val value = txt.ifBlank { desc }
            if (value.length in 2..80 && value !in parts) {
                parts.add(value)
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChildAt(i))
            }
        }
        for (w in 0 until structure.windowNodeCount.coerceAtMost(2)) {
            traverse(structure.getWindowNodeAt(w).rootViewNode)
        }
        return parts.joinToString(" | ").take(260)
    }
}

@Composable
fun AetherAssistantOverlaySheet(
    state: AssistantUiState,
    assistScreenHint: String,
    onMicTap: () -> Unit,
    onSubmitText: (String) -> Unit,
    onAbortTap: () -> Unit,
    onOpenFullHud: () -> Unit,
    onCloseSheet: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val isBusy = state.coreMode == CoreMode.THINKING ||
        state.coreMode == CoreMode.MULTITASKING ||
        state.coreMode == CoreMode.OBSERVING_SCREEN

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .testTag("assistant_overlay_sheet"),
            shape = RoundedCornerShape(24.dp),
            color = DeepCockpit.copy(alpha = 0.94f),
            border = BorderStroke(
                width = 1.dp,
                color = when (state.coreMode) {
                    CoreMode.LISTENING -> ArcCyan
                    CoreMode.MULTITASKING, CoreMode.OBSERVING_SCREEN -> StealthEmerald
                    CoreMode.THINKING -> SolarGold
                    CoreMode.ERROR -> AlertCoral
                    else -> ArcCyan.copy(alpha = 0.45f)
                }
            ),
            tonalElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Aether Assistant Active",
                        tint = ArcCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${state.preferences.persona.displayName} · System Overlay",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = HudTextPrimary
                        )
                        Text(
                            text = if (state.coreMode == CoreMode.IDLE) {
                                "0-Mic OS Trigger Ready"
                            } else {
                                state.coreMode.statusLabel
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary
                        )
                    }

                    IconButton(
                        onClick = onOpenFullHud,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("overlay_expand_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Open full Aether HUD",
                            tint = ArcCyan
                        )
                    }
                    IconButton(
                        onClick = onCloseSheet,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("overlay_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss assistant overlay",
                            tint = HudTextSecondary
                        )
                    }
                }

                if (isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = StealthEmerald,
                        trackColor = HudTextPrimary.copy(alpha = 0.2f)
                    )
                }

                val bodyText = when {
                    state.partialTranscript.isNotBlank() -> state.partialTranscript
                    state.streamingReply.isNotBlank() -> state.streamingReply
                    state.errorMessage != null -> state.errorMessage
                    state.reActProgressLabel.isNotBlank() && isBusy -> state.reActProgressLabel
                    state.latestReply.isNotBlank() -> state.latestReply
                    assistScreenHint.isNotBlank() -> "Screen read: $assistScreenHint"
                    else -> "Speak or type any multi-app command."
                }
                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HudTextPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("overlay_status_text")
                )

                if (state.activeTasks.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(state.activeTasks.take(6), key = { it.id }) { task ->
                            val chipColor = when (task.status) {
                                TaskStatus.DONE -> StealthEmerald
                                TaskStatus.RUNNING -> SolarGold
                                TaskStatus.FAILED, TaskStatus.TIMED_OUT -> AlertCoral
                                else -> HudTextSecondary
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = HudTextPrimary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, chipColor.copy(alpha = 0.65f))
                            ) {
                                Text(
                                    text = "${task.step.label}: ${task.status.name.lowercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chipColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Command across apps…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    onSubmitText(inputText)
                                    inputText = ""
                                }
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("overlay_command_input")
                    )

                    if (inputText.isNotBlank()) {
                        FilledTonalIconButton(
                            onClick = {
                                onSubmitText(inputText)
                                inputText = ""
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("overlay_send_button"),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = ArcCyan.copy(alpha = 0.22f),
                                contentColor = ArcCyan
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send command"
                            )
                        }
                    } else if (isBusy) {
                        FilledTonalIconButton(
                            onClick = onAbortTap,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("overlay_abort_button"),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = AlertCoral.copy(alpha = 0.22f),
                                contentColor = AlertCoral
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Abort active tasks"
                            )
                        }
                    } else {
                        FilledTonalIconButton(
                            onClick = onMicTap,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("overlay_mic_button"),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (state.coreMode == CoreMode.LISTENING) {
                                    SolarGold.copy(alpha = 0.28f)
                                } else {
                                    ArcCyan.copy(alpha = 0.22f)
                                },
                                contentColor = if (state.coreMode == CoreMode.LISTENING) {
                                    SolarGold
                                } else {
                                    ArcCyan
                                }
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = if (state.coreMode == CoreMode.LISTENING) {
                                    "Stop listening"
                                } else {
                                    "Start voice input"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
