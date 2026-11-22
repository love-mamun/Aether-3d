package com.example.automation

import com.example.ai.ActionType
import com.example.ai.PlannedStep
import org.json.JSONArray
import org.json.JSONObject

data class RecordedMacroStep(
    val packageName: String,
    val appLabel: String,
    val actionKind: String, // "CLICK" | "INPUT"
    val label: String,
    val textPayload: String = "",
    val normX: Int = 500,
    val normY: Int = 500,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Coordinates cross-app UI macro recording via AetherAccessibilityService (skipping password
 * fields cleanly) and compiles recorded interaction steps into DAG-executable [PlannedStep] lists.
 */
object ScreenMacroManager {

    @Volatile
    var isRecording: Boolean = false
        private set

    private val lock = Any()
    private val recordedSteps = mutableListOf<RecordedMacroStep>()

    @Volatile
    var onRecordingStateChanged: ((recording: Boolean, stepCount: Int) -> Unit)? = null

    fun startRecording() {
        synchronized(lock) {
            recordedSteps.clear()
            isRecording = true
        }
        onRecordingStateChanged?.invoke(true, 0)
    }

    fun recordInteraction(step: RecordedMacroStep) {
        val count = synchronized(lock) {
            if (!isRecording) return
            // Collapse rapid sequential typing into the same field into one clean INPUT step
            val last = recordedSteps.lastOrNull()
            if (last != null &&
                step.actionKind == "INPUT" &&
                last.actionKind == "INPUT" &&
                last.packageName == step.packageName &&
                last.label == step.label &&
                (step.timestampMs - last.timestampMs) < 3_000L
            ) {
                recordedSteps[recordedSteps.lastIndex] = step
            } else {
                // Deduplicate accidental double-click within 350ms
                if (last != null &&
                    step.actionKind == "CLICK" &&
                    last.actionKind == "CLICK" &&
                    last.packageName == step.packageName &&
                    last.label == step.label &&
                    (step.timestampMs - last.timestampMs) < 350L
                ) {
                    return
                }
                recordedSteps.add(step)
            }
            recordedSteps.size
        }
        onRecordingStateChanged?.invoke(true, count)
    }

    fun stopAndDrainSteps(): List<RecordedMacroStep> {
        val snapshot = synchronized(lock) {
            isRecording = false
            val copy = recordedSteps.toList()
            recordedSteps.clear()
            copy
        }
        onRecordingStateChanged?.invoke(false, 0)
        return snapshot
    }

    fun cancelRecording() {
        synchronized(lock) {
            isRecording = false
            recordedSteps.clear()
        }
        onRecordingStateChanged?.invoke(false, 0)
    }

    fun currentStepCount(): Int = synchronized(lock) { recordedSteps.size }

    fun encodeStepsJson(steps: List<RecordedMacroStep>): String {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("packageName", s.packageName)
                    put("appLabel", s.appLabel)
                    put("actionKind", s.actionKind)
                    put("label", s.label)
                    put("textPayload", s.textPayload)
                    put("normX", s.normX)
                    put("normY", s.normY)
                }
            )
        }
        return arr.toString()
    }

    fun decodeStepsJson(json: String): List<RecordedMacroStep> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                RecordedMacroStep(
                    packageName = obj.optString("packageName", ""),
                    appLabel = obj.optString("appLabel", ""),
                    actionKind = obj.optString("actionKind", "CLICK"),
                    label = obj.optString("label", ""),
                    textPayload = obj.optString("textPayload", ""),
                    normX = obj.optInt("normX", 500).coerceIn(0, 1000),
                    normY = obj.optInt("normY", 500).coerceIn(0, 1000)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun compileMacroToDagSteps(macroSteps: List<RecordedMacroStep>): List<PlannedStep> {
        if (macroSteps.isEmpty()) return emptyList()
        val planned = mutableListOf<PlannedStep>()
        var lastPkg = ""
        var stepIdx = 0

        for (item in macroSteps) {
            val prevDep = planned.lastOrNull()?.stepId?.let { listOf(it) } ?: emptyList()

            // Launch target app if package changed and differs from Aether itself
            if (item.packageName.isNotBlank() &&
                item.packageName != lastPkg &&
                item.packageName != "com.example" &&
                !item.packageName.startsWith("com.aistudio.aether3d")
            ) {
                val launchId = "macro_${stepIdx++}"
                val targetName = item.appLabel.ifBlank { item.packageName }
                planned.add(
                    PlannedStep(
                        stepId = launchId,
                        actionType = ActionType.LAUNCH_APP,
                        label = "Open $targetName",
                        target = targetName,
                        dependsOn = prevDep,
                        timeoutMs = 8_000L
                    )
                )
                lastPkg = item.packageName
            }

            val curDep = planned.lastOrNull()?.stepId?.let { listOf(it) } ?: emptyList()
            val actId = "macro_${stepIdx++}"
            if (item.actionKind == "INPUT" && item.textPayload.isNotBlank()) {
                planned.add(
                    PlannedStep(
                        stepId = actId,
                        actionType = ActionType.UI_INPUT_TEXT,
                        label = "Type in ${item.label.ifBlank { "field" }}",
                        target = item.label,
                        textPayload = item.textPayload,
                        dependsOn = curDep,
                        timeoutMs = 7_000L
                    )
                )
            } else if (item.label.isNotBlank()) {
                planned.add(
                    PlannedStep(
                        stepId = actId,
                        actionType = ActionType.UI_TAP_TEXT,
                        label = "Tap ${item.label.take(24)}",
                        target = item.label,
                        dependsOn = curDep,
                        timeoutMs = 7_000L
                    )
                )
            } else {
                planned.add(
                    PlannedStep(
                        stepId = actId,
                        actionType = ActionType.UI_TAP_COORDINATES,
                        label = "Tap (${item.normX}, ${item.normY})",
                        intValue = item.normX,
                        secondaryIntValue = item.normY,
                        dependsOn = curDep,
                        timeoutMs = 7_000L
                    )
                )
            }
        }
        return planned
    }
}
