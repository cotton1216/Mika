package com.xxt.zl.ai.ruyiremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.RemoteJob
import com.xxt.zl.ai.ruyiremote.session.RemoteJobOutput
import com.xxt.zl.ai.ruyiremote.session.RemoteJobsState
import com.xxt.zl.ai.ruyiremote.session.SelectedRemoteJob
import com.xxt.zl.ai.ruyiremote.ui.theme.RuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space
import kotlin.math.roundToLong

/**
 * 抽屉里的后台任务面板：列表态与详情态共用一块可用高度。
 *
 * 面板只读 [RemoteJobsState]，打开/关闭/停止/重试全部交回调用方。这样同一个组合函数
 * 既能放在抽屉里，也能单独预览；不持有控制器就不会和会话生命周期互相牵制。
 *
 * 屏幕上每个字都来自状态里的真实字段：没有输出就是没有输出，没有进度就不显示进度，
 * 不写 0 也不写「未知」占位（`status` 真的为空时由 [RemoteJob.statusLabel] 统一兜底）。
 */
@Composable
fun RemoteJobsPanel(
    state: RemoteJobsState,
    onOpenJob: (String) -> Unit,
    onCloseJob: () -> Unit,
    onKill: (String) -> Unit,
    onRetryList: () -> Unit,
    onRetryFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 已结束的任务用 finishedAt，仍在跑的用「现在」算已运行时长；重组时刷新即可。
    val now = System.currentTimeMillis()
    Column(modifier.fillMaxSize()) {
        val selected = state.selected
        if (selected == null) {
            JobList(
                state = state,
                now = now,
                onOpenJob = onOpenJob,
                onRetryList = onRetryList,
                modifier = Modifier.weight(1f),
            )
        } else {
            JobDetail(
                state = state,
                selected = selected,
                now = now,
                onCloseJob = onCloseJob,
                onKill = onKill,
                onRetryFollow = onRetryFollow,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* ------------------------------------------------------------------ 列表态 */

@Composable
private fun JobList(
    state: RemoteJobsState,
    now: Long,
    onOpenJob: (String) -> Unit,
    onRetryList: () -> Unit,
    modifier: Modifier,
) {
    val p = LocalRuyiPalette.current
    val error = state.error
    when {
        error != null -> Column(
            modifier.fillMaxWidth().padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = p.danger,
            )
            LinkText("重新读取", onRetryList)
        }

        state.loading && state.jobs.isEmpty() -> Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "正在读取后台任务…",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
                TypingDots(color = p.textTertiary)
            }
        }

        state.jobs.isEmpty() -> Column(
            modifier.fillMaxSize().padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "这个会话没有后台任务",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
            )
            Text(
                "桌面端跑命令、构建这类长任务时会出现在这里",
                style = MaterialTheme.typography.bodySmall,
                color = p.textTertiary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }

        else -> LazyColumn(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(state.jobs, key = { it.id }) { job ->
                JobRow(job = job, now = now, onClick = { onOpenJob(job.id) })
            }
        }
    }
}

@Composable
private fun JobRow(job: RemoteJob, now: Long, onClick: () -> Unit) {
    val p = LocalRuyiPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .clickable(onClick = onClick)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                job.title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = p.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(Space.s))
            StatusChip(job.statusLabel, statusTone(job.status, p))
        }
        job.progress?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
            )
        }
        job.detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = p.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = buildList {
            formatElapsed(job, now).takeIf { it.isNotEmpty() }?.let(::add)
            if (job.outputTotal > 0) add(formatBytes(job.outputTotal))
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, tone: Color) {
    val p = LocalRuyiPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = if (p.dark) 0.20f else 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tone,
        )
    }
}

/* ------------------------------------------------------------------ 详情态 */

@Composable
private fun JobDetail(
    state: RemoteJobsState,
    selected: SelectedRemoteJob,
    now: Long,
    onCloseJob: () -> Unit,
    onKill: (String) -> Unit,
    onRetryFollow: () -> Unit,
    modifier: Modifier,
) {
    val p = LocalRuyiPalette.current
    val job = selected.job
    val output = selected.output

    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onCloseJob)
                    .padding(horizontal = Space.s, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphBack(size = 16.dp, tint = p.textSecondary)
                Spacer(Modifier.width(Space.xs))
                Text(
                    "任务列表",
                    style = MaterialTheme.typography.labelMedium,
                    color = p.textSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (job.killable) {
                KillButton(killing = state.killing, onClick = { onKill(job.id) })
            }
        }

        Text(
            job.title,
            style = MaterialTheme.typography.titleMedium,
            color = p.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val meta = buildList {
            add(job.statusLabel)
            job.progress?.takeIf { it.isNotBlank() }?.let(::add)
            formatElapsed(job, now).takeIf { it.isNotEmpty() }?.let(::add)
        }
        Text(
            meta.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )

        state.killError?.let { HintBar(it, p.danger) }
        state.killNotice?.let { HintBar(it, p.textSecondary) }

        output.error?.let {
            InlineError(it, retryLabel = "重新读取", onRetry = onRetryFollow)
        }
        if (output.lostEarlier) {
            Text(
                "更早的输出已经拿不到了",
                style = MaterialTheme.typography.labelSmall,
                color = p.warning,
            )
        }
        if (output.truncated) {
            Text(
                "只保留最近的内容",
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
            )
        }

        OutputBody(output, Modifier.weight(1f))
    }
}

@Composable
private fun KillButton(killing: Boolean, onClick: () -> Unit) {
    val p = LocalRuyiPalette.current
    val tone = if (killing) p.textTertiary else p.danger
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = if (p.dark) 0.20f else 0.12f))
            .clickable(enabled = !killing, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphStop(size = 12.dp, tint = tone)
        Text(
            if (killing) "正在停止…" else "停止",
            style = MaterialTheme.typography.labelSmall,
            color = tone,
        )
    }
}

@Composable
private fun OutputBody(output: RemoteJobOutput, modifier: Modifier) {
    val p = LocalRuyiPalette.current
    val scroll = rememberScrollState()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.card),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (output.text.isEmpty()) {
                Text(
                    "还没有输出",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textTertiary,
                )
            } else {
                Text(
                    output.text,
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.text,
                )
            }
            if (output.streaming) {
                Text(
                    "仍在输出…",
                    style = MaterialTheme.typography.labelSmall,
                    color = p.textTertiary,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 小块 */

/** 浅底提示条：只陈述一句状态，不带动作。 */
@Composable
private fun HintBar(text: String, color: Color) {
    val p = LocalRuyiPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (p.dark) 0.16f else 0.08f))
            .padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/** 文字按钮：只给「重新读取」这类单一动作。 */
@Composable
private fun LinkText(label: String, onClick: () -> Unit) {
    val p = LocalRuyiPalette.current
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = Space.xs, horizontal = Space.xs),
        style = MaterialTheme.typography.labelMedium,
        color = p.accent,
    )
}

private fun statusTone(status: String, p: RuyiPalette): Color =
    when (status) {
        "running" -> p.success
        "stopping" -> p.warning
        "completed" -> p.textSecondary
        "killed", "failed" -> p.danger
        else -> p.textTertiary
    }

/* ------------------------------------------------------------------ 格式化 */

/**
 * 已运行时长。`startedAt` 缺失时返回空串，界面整段不渲染；
 * 已结束用 `finishedAt`，仍在跑用调用处传进来的当前时刻。
 */
private fun formatElapsed(job: RemoteJob, nowMs: Long): String {
    if (job.startedAt <= 0) return ""
    // `finishedAt` 偶尔会以 0 出现（表示还没有结束），不能拿它当年份 0 的结束时刻。
    val end = job.finishedAt?.takeIf { it > 0 } ?: nowMs
    val seconds = ((end - job.startedAt).coerceAtLeast(0)) / 1000
    return if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
}

/** 字节数按 1024 进制进位，保留一位小数；0 返回空串。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (true) {
        val tenths = (value * 10).roundToLong()
        // 四舍五入后如果已经够到下一个单位（例如 1023.96 KB），先升位再打印。
        if (tenths < 10240 || unit == units.lastIndex) {
            return "${tenths / 10}.${tenths % 10} ${units[unit]}"
        }
        value /= 1024.0
        unit++
    }
}
