package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ai.ActiveTaskItem
import com.example.ai.CoreMode
import com.example.ai.TaskStatus
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SmokedGlassElevated
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import kotlin.math.abs

private data class NodeBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val rightCenter: Offset get() = Offset(left + width, top + height / 2f)
    val leftCenter: Offset get() = Offset(left, top + height / 2f)
}

/**
 * Pure topological depth mapper for organizing active DAG tasks into left-to-right execution columns.
 */
fun computeDagColumns(tasks: List<ActiveTaskItem>): List<List<ActiveTaskItem>> {
    if (tasks.isEmpty()) return emptyList()
    val byId = tasks.associateBy { it.id }
    val memo = mutableMapOf<String, Int>()
    val visiting = mutableSetOf<String>()

    fun depthOf(id: String): Int {
        memo[id]?.let { return it }
        if (!visiting.add(id)) return 0
        val item = byId[id]
        val d = if (item == null || item.dependsOn.isEmpty()) {
            0
        } else {
            (item.dependsOn.mapNotNull { depId -> byId[depId]?.let { depthOf(it.id) } }.maxOrNull() ?: -1) + 1
        }
        visiting.remove(id)
        memo[id] = d
        return d
    }

    tasks.forEach { depthOf(it.id) }
    val maxCol = memo.values.maxOrNull() ?: 0
    return (0..maxCol).map { col ->
        tasks.filter { (memo[it.id] ?: 0) == col }
    }.filter { it.isNotEmpty() }
}

@Composable
fun LiveTaskGraphCard(
    tasks: List<ActiveTaskItem>,
    coreMode: CoreMode,
    matchedPlaybookLabel: String = "",
    lastAutoTriggerLabel: String = "",
    onAbortTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    var graphMode by remember { mutableStateOf(true) }
    var selectedTaskId by remember(tasks) {
        mutableStateOf(
            tasks.firstOrNull { it.status == TaskStatus.RUNNING }?.id ?: tasks.firstOrNull()?.id
        )
    }

    val columns = remember(tasks) { computeDagColumns(tasks) }
    val nodeBoundsMap = remember(tasks) { mutableMapOf<String, NodeBounds>() }

    val pulseTransition = rememberInfiniteTransition(label = "dag_edge_flow")
    val dashPhase by pulseTransition.animateFloat(
        initialValue = 24f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dash_phase"
    )

    Surface(
        color = SmokedGlass,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_task_graph_card")
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountTree,
                        contentDescription = "Task execution graph",
                        tint = accent,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    val doneCount = tasks.count { it.status == TaskStatus.DONE }
                    Text(
                        text = "LIVE TASK DAG ($doneCount/${tasks.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { graphMode = !graphMode },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("task_graph_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (graphMode) Icons.Filled.List else Icons.Filled.AccountTree,
                            contentDescription = if (graphMode) "Switch to compact list view" else "Switch to DAG graph view",
                            tint = HudTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (coreMode == CoreMode.MULTITASKING || coreMode == CoreMode.THINKING) {
                        IconButton(
                            onClick = onAbortTasks,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("abort_tasks_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Abort active tasks",
                                tint = AlertCoral,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (matchedPlaybookLabel.isNotBlank() || lastAutoTriggerLabel.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (matchedPlaybookLabel.isNotBlank()) {
                        Surface(
                            color = NeuralViolet.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, NeuralViolet.copy(alpha = 0.5f)),
                            modifier = Modifier.testTag("matched_playbook_badge")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = NeuralViolet,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = matchedPlaybookLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeuralViolet,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (lastAutoTriggerLabel.isNotBlank()) {
                        Surface(
                            color = SolarGold.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, SolarGold.copy(alpha = 0.45f)),
                            modifier = Modifier.testTag("auto_trigger_badge")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = SolarGold,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = lastAutoTriggerLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SolarGold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            if (graphMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("live_task_graph_canvas")
                ) {
                    Box {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            tasks.forEach { targetTask ->
                                val dst = nodeBoundsMap[targetTask.id] ?: return@forEach
                                targetTask.dependsOn.forEach { depId ->
                                    val src = nodeBoundsMap[depId] ?: return@forEach
                                    val srcTask = tasks.firstOrNull { it.id == depId }
                                    val edgeColor = when {
                                        targetTask.status == TaskStatus.FAILED || srcTask?.status == TaskStatus.FAILED -> AlertCoral.copy(alpha = 0.78f)
                                        targetTask.status == TaskStatus.RUNNING || srcTask?.status == TaskStatus.RUNNING -> SolarGold
                                        srcTask?.status == TaskStatus.DONE -> StealthEmerald.copy(alpha = 0.85f)
                                        else -> accent.copy(alpha = 0.42f)
                                    }
                                    val start = src.rightCenter
                                    val end = dst.leftCenter
                                    val dx = abs(end.x - start.x).coerceAtLeast(20f) * 0.52f
                                    val path = Path().apply {
                                        moveTo(start.x, start.y)
                                        cubicTo(
                                            start.x + dx, start.y,
                                            end.x - dx, end.y,
                                            end.x, end.y
                                        )
                                    }
                                    val isFlowing = targetTask.status == TaskStatus.RUNNING || srcTask?.status == TaskStatus.RUNNING
                                    drawPath(
                                        path = path,
                                        color = edgeColor,
                                        style = Stroke(
                                            width = if (isFlowing) 2.2.dp.toPx() else 1.5.dp.toPx(),
                                            cap = StrokeCap.Round,
                                            pathEffect = if (isFlowing) {
                                                PathEffect.dashPathEffect(floatArrayOf(12f, 8f), dashPhase)
                                            } else {
                                                null
                                            }
                                        )
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            columns.forEach { columnTasks ->
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    columnTasks.forEach { task ->
                                        val isSelected = selectedTaskId == task.id
                                        DagNodeCapsule(
                                            task = task,
                                            isSelected = isSelected,
                                            accent = accent,
                                            onClick = { selectedTaskId = task.id },
                                            modifier = Modifier.onGloballyPositioned { coords ->
                                                val pos = coords.positionInParent()
                                                val sz: IntSize = coords.size
                                                nodeBoundsMap[task.id] = NodeBounds(
                                                    left = pos.x,
                                                    top = pos.y,
                                                    width = sz.width.toFloat(),
                                                    height = sz.height.toFloat()
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val inspected = tasks.firstOrNull { it.id == selectedTaskId }
                AnimatedVisibility(visible = inspected != null) {
                    if (inspected != null) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = SmokedGlassElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dag_node_inspector")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${inspected.step.label}: ${inspected.detail.ifBlank { inspected.status.name }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HudTextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (inspected.elapsedMs > 0L) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${inspected.elapsedMs}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                tasks.take(6).forEach { task ->
                    CompactDagTaskRow(task = task, accent = accent)
                }
            }
        }
    }
}

@Composable
private fun DagNodeCapsule(
    task: ActiveTaskItem,
    isSelected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when (task.status) {
        TaskStatus.DONE -> StealthEmerald
        TaskStatus.RUNNING -> SolarGold
        TaskStatus.FAILED -> AlertCoral
        TaskStatus.TIMED_OUT -> SolarGold
        TaskStatus.NEEDS_CONFIRMATION -> SolarGold
        TaskStatus.SKIPPED -> HudTextSecondary
        TaskStatus.QUEUED -> accent
    }

    Surface(
        color = if (isSelected) statusColor.copy(alpha = 0.16f) else SmokedGlassElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected || task.status == TaskStatus.RUNNING) 1.5.dp else 1.dp,
            color = statusColor.copy(alpha = if (isSelected) 0.9f else 0.45f)
        ),
        modifier = modifier
            .widthIn(min = 128.dp, max = 188.dp)
            .minimumInteractiveComponentSize()
            .testTag("dag_node_${task.id}")
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskStatusSmallIcon(status = task.status, accent = statusColor)
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.step.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (task.status == TaskStatus.SKIPPED) HudTextSecondary else HudTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = when {
                    task.elapsedMs > 0L -> "${task.status.name} · ${task.elapsedMs}ms"
                    task.detail.isNotBlank() -> task.detail
                    else -> task.status.name
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CompactDagTaskRow(task: ActiveTaskItem, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskStatusSmallIcon(status = task.status, accent = accent)
        Spacer(Modifier.width(8.dp))
        val depPrefix = if (task.dependsOn.isNotEmpty()) "↳ " else ""
        Text(
            text = "$depPrefix${task.step.label}",
            style = MaterialTheme.typography.labelMedium,
            color = if (task.status == TaskStatus.SKIPPED) HudTextSecondary else HudTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val timingSuffix = if (task.elapsedMs > 0L) " · ${task.elapsedMs}ms" else ""
        val rightLabel = "${task.detail}$timingSuffix".trim()
        if (rightLabel.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = rightLabel,
                style = MaterialTheme.typography.labelSmall,
                color = when (task.status) {
                    TaskStatus.TIMED_OUT -> SolarGold
                    TaskStatus.FAILED -> AlertCoral
                    else -> HudTextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TaskStatusSmallIcon(status: TaskStatus, accent: Color) {
    when (status) {
        TaskStatus.RUNNING -> CircularProgressIndicator(
            color = accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp)
        )
        TaskStatus.DONE -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Task done",
            tint = StealthEmerald,
            modifier = Modifier.size(14.dp)
        )
        TaskStatus.FAILED -> Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = "Task failed",
            tint = AlertCoral,
            modifier = Modifier.size(14.dp)
        )
        TaskStatus.TIMED_OUT -> Icon(
            imageVector = Icons.Filled.TimerOff,
            contentDescription = "Task timed out",
            tint = SolarGold,
            modifier = Modifier.size(14.dp)
        )
        TaskStatus.SKIPPED -> Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "Task skipped",
            tint = HudTextSecondary,
            modifier = Modifier.size(14.dp)
        )
        TaskStatus.NEEDS_CONFIRMATION -> Icon(
            imageVector = Icons.Filled.HourglassTop,
            contentDescription = "Task awaiting confirmation",
            tint = SolarGold,
            modifier = Modifier.size(14.dp)
        )
        TaskStatus.QUEUED -> Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = "Task queued",
            tint = accent,
            modifier = Modifier.size(14.dp)
        )
    }
}
