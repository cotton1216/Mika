package com.xxt.zl.ai.ruyiremote.ui.screen

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xxt.zl.ai.ruyiremote.session.AssistantRow
import com.xxt.zl.ai.ruyiremote.session.ChatRow
import com.xxt.zl.ai.ruyiremote.session.NoticeRow
import com.xxt.zl.ai.ruyiremote.session.ShareOutput
import com.xxt.zl.ai.ruyiremote.session.SharePrivacy
import com.xxt.zl.ai.ruyiremote.session.ShareScope
import com.xxt.zl.ai.ruyiremote.session.ShareContent
import com.xxt.zl.ai.ruyiremote.session.TEXT_SHARE_LIMIT
import com.xxt.zl.ai.ruyiremote.session.ToolRow
import com.xxt.zl.ai.ruyiremote.session.UserRow
import com.xxt.zl.ai.ruyiremote.session.buildShareContent
import com.xxt.zl.ai.ruyiremote.session.shareableRows
import com.xxt.zl.ai.ruyiremote.session.unavailableReason
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.RuyiPalette

/**
 * 一个面板管住「一段对话能变成什么」：范围、内容开关、多选、预览、出口。
 *
 * 刻意与参考端 `share_panel.dart` 对齐的是「预览 == 导出」：这里的预览直接渲染
 * [ShareContent.markdown] 的开头，文字/文件出口发出去的也是同一份字符串，不会
 * 出现预览里有、导出里没有的数据。
 *
 * 本期只接通「文字」和「文件」两个出口。图片需要长图渲染与 URI 授权、链接需要
 * 上传服务与访问范围鉴权，都还没做 —— 按钮禁用并写明原因，不放点了没反应的假按钮。
 * 文件出口走系统文件选择器（SAF），由用户自己选保存位置，App 不把会话正文写进
 * 私有存储，也不新增任何本地缓存。
 */
@Composable
fun ShareSheet(
    rows: List<ChatRow>,
    title: String,
    hostName: String,
    modelName: String?,
    anchorKey: String?,
    onDismiss: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val context = LocalContext.current

    var scope by remember { mutableStateOf(if (anchorKey != null) ShareScope.Reply else ShareScope.Whole) }
    var privacy by remember { mutableStateOf(SharePrivacy()) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var page by remember { mutableStateOf(SharePage.Home) }
    var scopeBeforePick by remember { mutableStateOf(scope) }
    var draftPicked by remember { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val shareable = remember(rows) { shareableRows(rows) }
    val content = remember(rows, scope, picked, privacy, anchorKey, title, hostName, modelName) {
        buildShareContent(
            rows = rows,
            scope = scope,
            pickedKeys = picked,
            anchorKey = anchorKey,
            privacy = privacy,
            title = title,
            hostName = hostName,
            modelName = modelName,
        )
    }
    // SAF 回调可能在若干次重组之后才触发，必须读最新的导出内容。
    val latestContent by rememberUpdatedState(content)

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri == null) {
            notice = "已取消保存"
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法打开所选文件的输出流")
            stream.use { it.write(latestContent.markdown.toByteArray(Charsets.UTF_8)) }
        }
        val failure = result.exceptionOrNull()
        error = failure?.let { "保存失败：${it.message ?: "未知错误"}" }
        if (failure == null) notice = "已写入所选文件"
    }

    fun shareText() {
        error = null
        notice = null
        when {
            content.isEmpty -> error = "这段范围里没有可分享的消息"
            content.textTooLarge -> error = "内容超过 $TEXT_SHARE_LIMIT 字，请用「文件」导出，避免系统分享静默截断"
            else -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "如意会话" })
                    putExtra(Intent.EXTRA_TEXT, content.markdown)
                }
                val result = runCatching {
                    context.startActivity(Intent.createChooser(send, "分享对话"))
                }
                val failure = result.exceptionOrNull()
                if (failure != null) error = "调起分享失败：${failure.message ?: "未知错误"}"
                else notice = "已调起系统分享"
            }
        }
    }

    fun saveFile() {
        error = null
        notice = null
        if (content.isEmpty) {
            error = "这段范围里没有可分享的消息"
            return
        }
        val result = runCatching { createDocument.launch("ruyi-share.md") }
        val failure = result.exceptionOrNull()
        if (failure != null) error = "打开文件选择器失败：${failure.message ?: "未知错误"}"
    }

    BackHandler {
        if (page == SharePage.Picker) page = SharePage.Home else onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(p.bgElevated)
                    // 吃掉面板内部的点击，否则会冒泡到外层遮罩把面板关掉。
                    .clickable(onClick = {})
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                when (page) {
                    SharePage.Home -> ShareHome(
                        p = p,
                        scope = scope,
                        privacy = privacy,
                        content = content,
                        pickedCount = picked.size,
                        hasAnchor = anchorKey != null,
                        onScope = { next ->
                            error = null
                            notice = null
                            if (next == ShareScope.Picked) {
                                scopeBeforePick = scope
                                draftPicked = picked
                                scope = ShareScope.Picked
                                page = SharePage.Picker
                            } else {
                                scope = next
                            }
                        },
                        onPrivacy = { privacy = it; notice = null },
                        onPick = {
                            scopeBeforePick = scope
                            draftPicked = picked
                            scope = ShareScope.Picked
                            page = SharePage.Picker
                        },
                        onText = ::shareText,
                        onFile = ::saveFile,
                        onDismiss = onDismiss,
                        error = error,
                        notice = notice,
                    )
                    SharePage.Picker -> SharePicker(
                        p = p,
                        rows = shareable,
                        selected = draftPicked,
                        onToggle = { key ->
                            draftPicked = if (key in draftPicked) draftPicked - key else draftPicked + key
                        },
                        onSelectAll = { draftPicked = shareable.map { it.key }.toSet() },
                        onClear = { draftPicked = emptySet() },
                        onCancel = {
                            scope = scopeBeforePick
                            page = SharePage.Home
                        },
                        onDone = {
                            if (draftPicked.isEmpty()) {
                                error = "至少选择一条消息"
                            } else {
                                picked = draftPicked
                                scope = ShareScope.Picked
                            }
                            page = SharePage.Home
                        },
                    )
                }
            }
        }
    }
}

private enum class SharePage { Home, Picker }

@Composable
private fun ColumnScope.ShareHome(
    p: RuyiPalette,
    scope: ShareScope,
    privacy: SharePrivacy,
    content: ShareContent,
    pickedCount: Int,
    hasAnchor: Boolean,
    onScope: (ShareScope) -> Unit,
    onPrivacy: (SharePrivacy) -> Unit,
    onPick: () -> Unit,
    onText: () -> Unit,
    onFile: () -> Unit,
    onDismiss: () -> Unit,
    error: String?,
    notice: String?,
) {
    SheetTopBar(p, title = "分享对话", onDismiss = onDismiss)
    Spacer(Modifier.height(12.dp))

    // 范围：从单条回复打开时才有「这条回复」；「选择消息」点了直接进多选页。
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasAnchor) {
            ScopeChip(p, "这条回复", scope == ShareScope.Reply) { onScope(ShareScope.Reply) }
        }
        ScopeChip(p, if (pickedCount > 0) "选中 $pickedCount 条" else "选择消息", scope == ShareScope.Picked) {
            onScope(ShareScope.Picked)
        }
        ScopeChip(p, "整段对话", scope == ShareScope.Whole) { onScope(ShareScope.Whole) }
    }
    if (scope == ShareScope.Picked) {
        Text(
            "点「调整」可重新选择消息",
            style = MaterialTheme.typography.labelSmall,
            color = p.textTertiary,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButtonLine(p, "调整选择", onPick)
    }

    Spacer(Modifier.height(12.dp))
    Text("包含内容", style = MaterialTheme.typography.labelLarge, color = p.textSecondary)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(p, "思考过程", privacy.thinking) { onPrivacy(privacy.copy(thinking = !privacy.thinking)) }
        ToggleChip(p, "工具调用", privacy.tools) { onPrivacy(privacy.copy(tools = !privacy.tools)) }
        ToggleChip(p, "图片", privacy.images) { onPrivacy(privacy.copy(images = !privacy.images)) }
    }
    Text(
        "凭据、授权头与本机路径始终自动隐藏；图片在文字/文件导出中只标注数量，不含原图。",
        style = MaterialTheme.typography.labelSmall,
        color = p.textTertiary,
        modifier = Modifier.padding(top = 6.dp),
    )

    Spacer(Modifier.height(14.dp))
    Text(
        if (content.isEmpty) "预览：这段范围里没有内容"
        else "预览（导出内容开头，与文字/文件导出完全一致）",
        style = MaterialTheme.typography.labelLarge,
        color = p.textSecondary,
    )
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.card)
            .border(1.dp, p.hairline, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = if (content.isEmpty) "请换一个范围，或先在会话里产生消息。"
            else content.markdown.take(2000) + if (content.markdown.length > 2000) "\n…（以下省略，导出时为完整内容）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = p.text,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "${content.messages} 条消息 · ${content.toolCalls} 次工具调用 · ${content.images} 张图片 · " +
            if (content.redactions > 0) "已隐藏 ${content.redactions} 处敏感信息" else "未发现敏感信息",
        style = MaterialTheme.typography.labelSmall,
        color = p.textTertiary,
    )

    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = p.danger, modifier = Modifier.padding(top = 6.dp))
    } else if (notice != null) {
        Text(notice, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
    }

    Spacer(Modifier.height(12.dp))
    Text("导出方式", style = MaterialTheme.typography.labelLarge, color = p.textSecondary)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShareOutput.values().forEach { output ->
            val reason = output.unavailableReason()
            OutputChip(
                p = p,
                label = when (output) {
                    ShareOutput.Text -> "文字"
                    ShareOutput.File -> "文件"
                    ShareOutput.Image -> "图片"
                    ShareOutput.Link -> "链接"
                },
                enabled = reason == null && !content.isEmpty,
                onClick = when (output) {
                    ShareOutput.Text -> onText
                    ShareOutput.File -> onFile
                    else -> ({})
                },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "本期可用：文字、文件。" +
            "图片：${ShareOutput.Image.unavailableReason()}；" +
            "链接：${ShareOutput.Link.unavailableReason()}。",
        style = MaterialTheme.typography.labelSmall,
        color = p.textTertiary,
    )
}

@Composable
private fun ColumnScope.SharePicker(
    p: RuyiPalette,
    rows: List<ChatRow>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    SheetTopBar(p, title = "选择消息（${selected.size} 条）", onDismiss = onCancel)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButtonLine(p, "全选", onSelectAll)
        TextButtonLine(p, "清空", onClear)
    }
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.weight(1f)) {
        items(rows, key = { it.key }) { row ->
            val checked = row.key in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (checked) p.accentSoft else p.card)
                    .clickable { onToggle(row.key) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { onToggle(row.key) })
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        rowLabel(row),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = p.text,
                    )
                    Text(
                        rowPreview(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(p, "取消", primary = false, onClick = onCancel, modifier = Modifier.weight(1f))
        ActionButton(p, "确定（${selected.size} 条）", primary = true, onClick = onDone, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SheetTopBar(p: RuyiPalette, title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = p.text,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(p.chipNeutral)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", style = MaterialTheme.typography.titleMedium, color = p.textSecondary)
        }
    }
}

@Composable
private fun ScopeChip(p: RuyiPalette, label: String, selected: Boolean, onClick: () -> Unit) =
    ChipBox(p, label, selected, onClick)

@Composable
private fun ToggleChip(p: RuyiPalette, label: String, selected: Boolean, onClick: () -> Unit) =
    ChipBox(p, (if (selected) "✓ " else "") + label, selected, onClick)

@Composable
private fun OutputChip(p: RuyiPalette, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) p.accent else p.chipNeutral)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) p.onAccent else p.textTertiary,
        )
    }
}

@Composable
private fun ChipBox(p: RuyiPalette, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) p.accentSoft else p.chipNeutral)
            .border(1.dp, if (selected) p.accent else p.hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) p.onAccentSoft else p.textSecondary,
        )
    }
}

@Composable
private fun TextButtonLine(p: RuyiPalette, label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = p.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun ActionButton(
    p: RuyiPalette,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) p.accent else p.chipNeutral)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) p.onAccent else p.textSecondary,
        )
    }
}

private fun rowLabel(row: ChatRow): String = when (row) {
    is UserRow -> "我"
    is AssistantRow -> "助手" + (row.model?.let { " · $it" } ?: "")
    is ToolRow -> "工具 · ${row.name}"
    is NoticeRow -> "提示"
}

private fun rowPreview(row: ChatRow): String = when (row) {
    is UserRow -> row.text
    is AssistantRow -> row.text
    is ToolRow -> row.summary
    is NoticeRow -> row.text
}.replace('\n', ' ').ifBlank { "（空）" }
