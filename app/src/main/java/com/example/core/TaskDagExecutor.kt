package com.example.core

import com.example.ai.ActionType
import com.example.ai.ActiveTaskItem
import com.example.ai.PlannedStep
import com.example.ai.TaskStatus
import com.example.system.ActionExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class DagExecutionSummary(
    val toolOutputs: List<String>,
    val completedCount: Int,
    val failedCount: Int,
    val timedOutCount: Int,
    val skippedCount: Int
)

data class ResolvedDagNode(
    val id: String,
    val step: PlannedStep,
    val dependencies: List<String>,
    val timeoutMs: Long
)

/**
 * Executes a DAG of [PlannedStep] nodes:
 * - Breaks cycles cleanly so the scheduler can never deadlock
 * - Runs independent / parallel-ready branches concurrently
 * - Applies implicit sequential UI ordering when UI steps follow app launches without explicit deps
 * - Enforces per-step timeouts and cascades SKIPPED status to dependents of failed/timed-out steps
 */
class TaskDagExecutor {

    fun buildDag(steps: List<PlannedStep>): List<ResolvedDagNode> {
        if (steps.isEmpty()) return emptyList()

        // Assign stable IDs
        val assignedIds = steps.mapIndexed { idx, step ->
            step.stepId.trim().ifBlank { "task_$idx" }
        }
        val idSet = assignedIds.toSet()

        // Track previous foreground UI/launch node so chained UI steps don't fire before an app opens
        var lastForegroundUiId: String? = null
        val rawNodes = steps.mapIndexed { idx, step ->
            val id = assignedIds[idx]
            val explicitDeps = step.dependsOn
                .map { it.trim() }
                .filter { it.isNotBlank() && it != id && it in idSet }
                .distinct()

            val implicitDeps = if (explicitDeps.isEmpty() && !step.isBackgroundParallel && isForegroundAction(step.actionType)) {
                val prev = lastForegroundUiId
                lastForegroundUiId = id
                if (prev != null) listOf(prev) else emptyList()
            } else {
                if (!step.isBackgroundParallel && isForegroundAction(step.actionType)) {
                    lastForegroundUiId = id
                }
                explicitDeps
            }

            ResolvedDagNode(
                id = id,
                step = step.copy(stepId = id, dependsOn = implicitDeps),
                dependencies = implicitDeps,
                timeoutMs = resolveTimeoutMs(step)
            )
        }

        return removeCycles(rawNodes)
    }

    private fun removeCycles(nodes: List<ResolvedDagNode>): List<ResolvedDagNode> {
        val orderIndex = nodes.withIndex().associate { it.value.id to it.index }
        val nodeMap = nodes.associateBy { it.id }

        // Keep only edges without cycles via DFS back-edge removal (deterministic)
        val state = mutableMapOf<String, Int>() // 0=unvisited, 1=visiting, 2=done
        val safeDeps = nodes.associate { it.id to it.dependencies.toMutableList() }

        fun dfs(id: String) {
            state[id] = 1
            val deps = safeDeps[id] ?: return
            val iterator = deps.iterator()
            while (iterator.hasNext()) {
                val depId = iterator.next()
                when (state[depId]) {
                    1 -> iterator.remove() // Back-edge causes cycle -> drop it
                    null -> if (depId in nodeMap) dfs(depId)
                }
            }
            state[id] = 2
        }

        nodes.sortedBy { orderIndex[it.id] ?: 0 }.forEach { node ->
            if (state[node.id] == null) dfs(node.id)
        }

        return nodes.map { node ->
            val cleaned = safeDeps[node.id].orEmpty()
            node.copy(dependencies = cleaned, step = node.step.copy(dependsOn = cleaned))
        }
    }

    fun initialTaskItems(nodes: List<ResolvedDagNode>): List<ActiveTaskItem> {
        return nodes.map { node ->
            ActiveTaskItem(
                id = node.id,
                step = node.step,
                status = TaskStatus.QUEUED,
                detail = if (node.dependencies.isNotEmpty()) {
                    "Waiting on ${node.dependencies.joinToString(", ")}"
                } else {
                    "Queued"
                },
                dependsOn = node.dependencies
            )
        }
    }

    suspend fun executeDag(
        nodes: List<ResolvedDagNode>,
        onTaskUpdate: (taskId: String, status: TaskStatus, detail: String, elapsedMs: Long, timedOut: Boolean) -> Unit,
        onTorchStateChanged: (Boolean) -> Unit,
        executor: suspend (PlannedStep) -> ActionExecutionResult
    ): DagExecutionSummary {
        if (nodes.isEmpty()) {
            return DagExecutionSummary(emptyList(), 0, 0, 0, 0)
        }

        val stateLock = Any()
        val statusMap = nodes.associate { it.id to TaskStatus.QUEUED }.toMutableMap()
        val outputsByStepId = mutableMapOf<String, String>()
        var lastOutputValue = ""
        val toolOutputs = mutableListOf<String>()
        var completed = 0
        var failed = 0
        var timedOutCount = 0
        var skipped = 0

        while (synchronized(stateLock) { statusMap.values.any { it == TaskStatus.QUEUED } }) {
            // 1. Cascade SKIPPED for any node whose dependency failed/timed out/skipped
            var skippedAny = true
            while (skippedAny) {
                skippedAny = false
                for (node in nodes) {
                    val blockedDep = synchronized(stateLock) {
                        if (statusMap[node.id] != TaskStatus.QUEUED) {
                            null
                        } else {
                            val depHit = node.dependencies.firstOrNull { dep ->
                                val st = statusMap[dep]
                                st == TaskStatus.FAILED || st == TaskStatus.TIMED_OUT || st == TaskStatus.SKIPPED
                            }
                            if (depHit != null) {
                                statusMap[node.id] = TaskStatus.SKIPPED
                                skipped++
                            }
                            depHit
                        }
                    }
                    if (blockedDep != null) {
                        onTaskUpdate(node.id, TaskStatus.SKIPPED, "Skipped ($blockedDep failed)", 0L, false)
                        skippedAny = true
                    }
                }
            }

            // 2. Gather ready wave (all dependencies DONE)
            val readyWave = synchronized(stateLock) {
                nodes.filter { node ->
                    statusMap[node.id] == TaskStatus.QUEUED &&
                        node.dependencies.all { dep -> statusMap[dep] == TaskStatus.DONE }
                }
            }

            if (readyWave.isEmpty()) {
                val leftover = synchronized(stateLock) {
                    nodes.filter { statusMap[it.id] == TaskStatus.QUEUED }.also { rem ->
                        rem.forEach { n ->
                            statusMap[n.id] = TaskStatus.SKIPPED
                            skipped++
                        }
                    }
                }
                for (node in leftover) {
                    onTaskUpdate(node.id, TaskStatus.SKIPPED, "Skipped (unresolved dep)", 0L, false)
                }
                break
            }

            coroutineScope {
                readyWave.map { node ->
                    async {
                        if (node.step.actionType == ActionType.SPEAK_ONLY ||
                            node.step.actionType == ActionType.SCREEN_EXPLAIN
                        ) {
                            synchronized(stateLock) {
                                statusMap[node.id] = TaskStatus.DONE
                                completed++
                            }
                            onTaskUpdate(node.id, TaskStatus.DONE, "Prepared", 0L, false)
                            return@async
                        }

                        // Small UI settle delay if node depends on an app launch or prior foreground automation
                        val hadLaunchDep = node.dependencies.any { depId ->
                            val depType = nodes.firstOrNull { it.id == depId }?.step?.actionType
                            depType == ActionType.LAUNCH_APP ||
                                depType == ActionType.OPEN_SETTINGS ||
                                depType == ActionType.SEARCH_IN_APP ||
                                depType == ActionType.SEND_APP_MESSAGE ||
                                depType == ActionType.POST_SOCIAL ||
                                depType == ActionType.PLAY_MEDIA
                        }
                        if (hadLaunchDep) {
                            delay(1_150L)
                        }

                        // Resolve {{stepId}} and {{last_output}} placeholders from completed prerequisites
                        val resolvedStep = synchronized(stateLock) {
                            resolveStepPlaceholders(node.step, outputsByStepId, lastOutputValue)
                        }

                        synchronized(stateLock) {
                            statusMap[node.id] = TaskStatus.RUNNING
                        }
                        val timeoutSec = (node.timeoutMs / 1000L).coerceAtLeast(1L)
                        onTaskUpdate(node.id, TaskStatus.RUNNING, "Running (${timeoutSec}s cap)...", 0L, false)
                        val startMs = System.currentTimeMillis()

                        try {
                            var result = withContext(Dispatchers.IO) {
                                withTimeout(node.timeoutMs) {
                                    executor(resolvedStep)
                                }
                            }
                            // Self-healing 1-shot retry for transient UI/network glitches
                            val canRetry = !result.success &&
                                isRetryableAction(resolvedStep.actionType) &&
                                (System.currentTimeMillis() - startMs) < (node.timeoutMs / 2L)
                            if (canRetry) {
                                delay(450L)
                                val remainingMs = (node.timeoutMs - (System.currentTimeMillis() - startMs)).coerceAtLeast(1_500L)
                                result = withContext(Dispatchers.IO) {
                                    withTimeout(remainingMs) {
                                        executor(resolvedStep)
                                    }
                                }
                            }

                            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            if (result.torchStateChanged != null) {
                                onTorchStateChanged(result.torchStateChanged)
                            }
                            synchronized(stateLock) {
                                val outputForContext = (result.toolOutputForSummary ?: result.detail).trim()
                                if (outputForContext.isNotBlank()) {
                                    outputsByStepId[node.id] = outputForContext
                                    lastOutputValue = outputForContext
                                }
                                result.toolOutputForSummary?.let { out -> toolOutputs.add(out) }
                                if (result.success) {
                                    statusMap[node.id] = TaskStatus.DONE
                                    completed++
                                } else {
                                    statusMap[node.id] = TaskStatus.FAILED
                                    failed++
                                }
                            }
                            onTaskUpdate(
                                node.id,
                                if (result.success) TaskStatus.DONE else TaskStatus.FAILED,
                                result.detail,
                                elapsed,
                                false
                            )
                        } catch (_: TimeoutCancellationException) {
                            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(node.timeoutMs)
                            synchronized(stateLock) {
                                statusMap[node.id] = TaskStatus.TIMED_OUT
                                timedOutCount++
                            }
                            onTaskUpdate(
                                node.id,
                                TaskStatus.TIMED_OUT,
                                "Timed out after ${timeoutSec}s",
                                elapsed,
                                true
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            synchronized(stateLock) {
                                statusMap[node.id] = TaskStatus.FAILED
                                failed++
                            }
                            onTaskUpdate(
                                node.id,
                                TaskStatus.FAILED,
                                e.localizedMessage ?: "Step execution error",
                                elapsed,
                                false
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        return synchronized(stateLock) {
            DagExecutionSummary(
                toolOutputs = toolOutputs.toList(),
                completedCount = completed,
                failedCount = failed,
                timedOutCount = timedOutCount,
                skippedCount = skipped
            )
        }
    }

    internal fun resolveStepPlaceholders(
        step: PlannedStep,
        outputsByStepId: Map<String, String>,
        lastOutput: String
    ): PlannedStep {
        fun substitute(raw: String): String {
            if (!raw.contains("{{")) return raw
            var out = raw.replace("{{last_output}}", lastOutput, ignoreCase = true)
            for ((k, v) in outputsByStepId) {
                out = out.replace("{{$k}}", v, ignoreCase = true)
            }
            return out
        }
        return step.copy(
            textPayload = substitute(step.textPayload),
            target = substitute(step.target),
            recipient = substitute(step.recipient)
        )
    }

    private fun isRetryableAction(actionType: ActionType): Boolean {
        return when (actionType) {
            ActionType.WEATHER_LOOKUP,
            ActionType.WIKIPEDIA_LOOKUP,
            ActionType.CURRENCY_CONVERT,
            ActionType.LAUNCH_APP,
            ActionType.PLAY_MEDIA,
            ActionType.UI_TAP_TEXT,
            ActionType.UI_INPUT_TEXT -> true
            else -> false
        }
    }

    internal fun resolveTimeoutMs(step: PlannedStep): Long {
        if (step.timeoutMs in 100L..90_000L) return step.timeoutMs
        return when (step.actionType) {
            ActionType.WEATHER_LOOKUP,
            ActionType.WIKIPEDIA_LOOKUP,
            ActionType.CURRENCY_CONVERT,
            ActionType.WEB_SEARCH -> 16_000L

            ActionType.LAUNCH_APP,
            ActionType.SEARCH_IN_APP,
            ActionType.OPEN_SETTINGS,
            ActionType.PLAY_MEDIA,
            ActionType.NAVIGATE_MAPS -> 35_000L

            ActionType.SEND_APP_MESSAGE -> 55_000L
            ActionType.POST_SOCIAL -> 70_000L

            ActionType.UI_TAP_TEXT,
            ActionType.UI_TAP_COORDINATES,
            ActionType.UI_INPUT_TEXT,
            ActionType.UI_SCROLL,
            ActionType.GLOBAL_ACTION -> 8_500L

            ActionType.READ_NOTIFICATIONS,
            ActionType.REPLY_NOTIFICATION,
            ActionType.OPEN_CAMERA_LENS -> 9_000L

            else -> 7_500L
        }
    }

    private fun isForegroundAction(actionType: ActionType): Boolean {
        return when (actionType) {
            ActionType.LAUNCH_APP,
            ActionType.SEARCH_IN_APP,
            ActionType.OPEN_SETTINGS,
            ActionType.DIAL_CONTACT,
            ActionType.SEND_MESSAGE,
            ActionType.SEND_APP_MESSAGE,
            ActionType.POST_SOCIAL,
            ActionType.DRAFT_EMAIL,
            ActionType.NAVIGATE_MAPS,
            ActionType.PLAY_MEDIA,
            ActionType.WEB_SEARCH,
            ActionType.CALENDAR_EVENT,
            ActionType.UI_TAP_TEXT,
            ActionType.UI_TAP_COORDINATES,
            ActionType.UI_INPUT_TEXT,
            ActionType.UI_SCROLL,
            ActionType.GLOBAL_ACTION,
            ActionType.OPEN_CAMERA_LENS -> true

            else -> false
        }
    }
}
