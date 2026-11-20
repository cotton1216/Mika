package com.xxt.zl.ai.ruyiremote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.CwdBlock
import com.xxt.zl.ai.ruyiremote.session.DayBlock
import com.xxt.zl.ai.ruyiremote.session.FolderBlock
import com.xxt.zl.ai.ruyiremote.session.GroupBlock
import com.xxt.zl.ai.ruyiremote.session.GroupDimension
import com.xxt.zl.ai.ruyiremote.session.GroupStatus
import com.xxt.zl.ai.ruyiremote.session.RemoteFolder
import com.xxt.zl.ai.ruyiremote.session.SessionBlock
import com.xxt.zl.ai.ruyiremote.session.SessionDay
import com.xxt.zl.ai.ruyiremote.session.SessionSummary
import com.xxt.zl.ai.ruyiremote.session.StatusBlock
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphChevron
import com.xxt.zl.ai.ruyiremote.ui.formatWhen
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/**
 * 会话分组列表的共享渲染（T11–T16 的分组能力，T17 抽出来给两页复用）。
 *
 * 抽屉页（[SessionDrawer]）与「全部会话」页（[HostWorkspaceScreen]）都用这一份渲染，
 * 保证分组结构、折叠交互、群聊入口完全一致。维度状态由上层共享（同一份持久化），
 * 折叠态是各页本地 UI 状态，由调用方持有并回传。
 *
 * 这里只负责把已经分好的 blocks 写进 LazyListScope；分桶用纯函数 `groupSessions`，
 * 由调用方在重组时计算（now 也由调用方注入，便于钉死时钟）。
 */
internal fun LazyListScope.groupedSessionItems(
    blocks: List<SessionBlock>,
    collapsedKeys: Set<String>,
    onToggleKey: (String) -> Unit,
    currentSessionId: String?,
    onToggleFolder: (String) -> Unit,
    onSelect: (SessionSummary) -> Unit,
    onOpenGroup: (String, List<SessionSummary>) -> Unit,
) {
    blocks.forEach { block ->
        when (block) {
            is FolderBlock -> {
                item(key = "folder-${block.folder.id}") {
                    FolderHeaderRow(
                        folder = block.folder,
                        count = block.items.size,
                        expanded = !block.folder.collapsed,
                        onClick = { onToggleFolder(block.folder.id) },
                    )
                }
                if (!block.folder.collapsed) {
                    items(block.items, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onClick = { onSelect(session) },
                            indent = true,
                        )
                    }
                }
            }

            is DayBlock -> {
                val key = "day-${block.day.name}"
                val expanded = key !in collapsedKeys
                item(key = key) {
                    DayHeaderRow(
                        label = dayLabel(block.day),
                        count = block.items.size,
                        expanded = expanded,
                        onClick = { onToggleKey(key) },
                    )
                }
                if (expanded) {
                    items(block.items, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onClick = { onSelect(session) },
                        )
                    }
                }
            }

            is StatusBlock -> {
                val key = "status-${block.status.name}"
                val expanded = key !in collapsedKeys
                item(key = key) {
                    DayHeaderRow(
                        label = statusLabel(block.status),
                        count = block.items.size,
                        expanded = expanded,
                        onClick = { onToggleKey(key) },
                    )
                }
                if (expanded) {
                    items(block.items, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onClick = { onSelect(session) },
                        )
                    }
                }
            }

            is CwdBlock -> {
                val key = "cwd-${block.label}"
                val expanded = key !in collapsedKeys
                item(key = key) {
                    DayHeaderRow(
                        label = block.label,
                        count = block.items.size,
                        expanded = expanded,
                        onClick = { onToggleKey(key) },
                    )
                }
                if (expanded) {
                    items(block.items, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onClick = { onSelect(session) },
                        )
                    }
                }
            }

            is GroupBlock -> {
                val key = "group-${block.group}"
                val expanded = key !in collapsedKeys
                item(key = key) {
                    // 群头一分为二：左侧点折叠，右侧「进群聊」进合并时间线页。
                    // 群成员仍是独立会话，直接点行进原会话；「进群聊」才看合并视图。
                    GroupHeaderRow(
                        label = "${block.group} · ${block.members.size} 人",
                        count = block.items.size,
                        expanded = expanded,
                        onToggle = { onToggleKey(key) },
                        onOpenChat = { onOpenGroup(block.group, block.items) },
                    )
                }
                if (expanded) {
                    items(block.items, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onClick = { onSelect(session) },
                            indent = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    indent: Boolean = false,
) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) p.bubbleUserSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "打开 ${session.displayTitle}"; role = Role.Button }
            // 文件夹成员向内缩进一档（对齐 dsh-Go SessionTile.folderIndent），一眼看出从属。
            .padding(
                start = if (indent) 24.dp else 12.dp,
                top = 11.dp,
                end = 12.dp,
                bottom = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (session.running) {
            Box(Modifier.size(7.dp).background(p.success, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    session.running -> "运行中 · ${formatWhen(session.updatedAt)}"
                    session.blank -> "空会话"
                    else -> formatWhen(session.updatedAt)
                },
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 文件夹组头：emoji 标记 + 名称 + 成员数 + 折叠箭头。点整行切换折叠（写回交给上层）。
 * 样式对齐 dsh-Go `DrawerFolderHeader`。
 */
@Composable
internal fun FolderHeaderRow(
    folder: RemoteFolder,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(p.float.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (count > 0) "文件夹 ${folder.name}，$count 条会话" else "文件夹 ${folder.name}，空"
                role = Role.Button
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // tint 是 0–7 索引，这里映射成一组稳定色点；无 emoji 时用色点占位，对齐 dsh-Go FolderMark。
        val mark = folder.tint?.let { FOLDER_TINT_COLORS[it % FOLDER_TINT_COLORS.size] } ?: p.accent
        if (!folder.emoji.isNullOrBlank()) {
            Text(folder.emoji, style = MaterialTheme.typography.bodyMedium)
        } else {
            Box(Modifier.size(8.dp).background(mark, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            folder.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) {
            Text("$count", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.rotate(if (expanded) 270f else 90f)) {
            GlyphChevron(size = 15.dp, tint = p.textTertiary)
        }
    }
}

/**
 * 按天分组的可折叠组头：标题 + 条数 + 箭头。状态 / 目录维度也复用它。
 */
@Composable
internal fun DayHeaderRow(
    label: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (count > 0) "$label，$count 条会话" else "$label，空"
                role = Role.Button
            }
            .padding(start = 4.dp, top = 8.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = p.textTertiary,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text("$count", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.rotate(if (expanded) 270f else 90f)) {
            GlyphChevron(size = 14.dp, tint = p.textTertiary)
        }
    }
}

/**
 * 群头：左侧标题区点折叠，右侧「进群聊」按钮进合并时间线页。
 * 群成员本身仍是独立会话，直接点成员行进原会话。
 */
@Composable
internal fun GroupHeaderRow(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(p.float.copy(alpha = 0.72f))
            .padding(start = 4.dp, top = 8.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .semantics {
                    contentDescription = if (count > 0) "$label，$count 条会话" else "$label，空"
                    role = Role.Button
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = p.textTertiary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count > 0) {
                Text("$count", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.rotate(if (expanded) 270f else 90f)) {
                GlyphChevron(size = 14.dp, tint = p.textTertiary)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "进群聊",
            style = MaterialTheme.typography.labelMedium,
            color = p.onAccentSoft,
            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .background(p.accentSoft)
                .clickable(onClick = onOpenChat)
                .semantics { contentDescription = "打开 $label 的合并群聊"; role = Role.Button }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 分天桶的中文标题，对齐 dsh-Go sessionDayLabels。 */
internal fun dayLabel(day: SessionDay): String = when (day) {
    SessionDay.TODAY -> "今天"
    SessionDay.YESTERDAY -> "昨天"
    SessionDay.THIS_WEEK -> "本周"
    SessionDay.EARLIER -> "更早"
}

/** 运行状态桶的中文标题。 */
internal fun statusLabel(status: GroupStatus): String = when (status) {
    GroupStatus.RUNNING -> "进行中"
    GroupStatus.ENDED -> "已结束"
}

/** 块折叠键的开关：在集合里则移除（展开），不在则加入（折叠）。 */
internal fun toggleKey(keys: Set<String>, key: String): Set<String> =
    if (key in keys) keys - key else keys + key

/**
 * 分组维度切换控件：四档并排，当前档用强调色胶囊高亮，其余为次级文字。
 */
@Composable
internal fun DimensionSwitcher(dimension: GroupDimension, onSelect: (GroupDimension) -> Unit) {
    val p = LocalRuyiPalette.current
    val options = listOf(
        GroupDimension.DAY to "按天",
        GroupDimension.STATUS to "按状态",
        GroupDimension.CWD to "按目录",
        GroupDimension.GROUP to "按群",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { index, (value, label) ->
            if (index > 0) Spacer(Modifier.width(Space.s))
            val selected = value == dimension
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) p.onAccentSoft else p.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (selected) p.accentSoft else Color.Transparent)
                    .clickable { onSelect(value) }
                    .semantics { contentDescription = "按${label}分组"; role = Role.Button }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/** 文件夹色点调色板（8 色，对齐 dsh-Go FOLDER_MARK_TINTS）。 */
internal val FOLDER_TINT_COLORS = listOf(
    Color(0xFF5B8DEF), Color(0xFF37B98E), Color(0xFFE8913A), Color(0xFFD96A6A),
    Color(0xFF8C6FD6), Color(0xFF4CA8B8), Color(0xFFC46FA8), Color(0xFF7A8A6A),
)
