package com.xxt.zl.ai.ruyiremote.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.AskOption
import com.xxt.zl.ai.ruyiremote.session.ApprovalDecision
import com.xxt.zl.ai.ruyiremote.session.MessageImage
import com.xxt.zl.ai.ruyiremote.session.PendingQuestions
import com.xxt.zl.ai.ruyiremote.session.ToolApproval
import com.xxt.zl.ai.ruyiremote.session.ToolPhase
import com.xxt.zl.ai.ruyiremote.session.ToolRow
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ToolCallCard(row: ToolRow, loadImage: suspend (MessageImage) -> ByteArray, waitingFor: String? = null, modifier: Modifier = Modifier) {
    val p = LocalRuyiPalette.current
    var expanded by remember(row.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val status = when {
        // 工具还没返回、但有一条等用户处理的请求：说「执行中」会让用户干等一个
        // 永远不会来的结果，他不知道卡在自己身上。
        waitingFor != null && row.phase == ToolPhase.Running -> "等待$waitingFor"
        row.phase == ToolPhase.Running -> "执行中"
        row.phase == ToolPhase.Succeeded -> "已完成"
        row.phase == ToolPhase.Failed -> "执行失败"
        else -> "已结束，未收到结果"
    }
    val waiting = waitingFor != null && row.phase == ToolPhase.Running
    val borderColor = if (waiting) p.accent else p.hairline
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, borderColor, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "收起工具详情" else "展开工具详情") { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = p.text)
            Text(
                "$status · ${if (expanded) "收起" else "详情"}",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    row.phase == ToolPhase.Failed -> p.danger
                    waiting -> p.accent
                    else -> p.textSecondary
                },
            )
        }
        if (row.summary.isNotBlank()) Text(row.summary, maxLines = if (expanded) Int.MAX_VALUE else 2, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        if (expanded) {
            if (row.arguments.isNotBlank()) ToolArguments(row.arguments)
            row.images.takeIf { it.isNotEmpty() }?.let { ToolImages(it, loadImage) }
            row.output?.takeIf { it.isNotBlank() }?.let { ToolDetail("结果", it) }
            row.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }
            if (row.output?.isEmpty() == true) Text("工具已返回，无文本输出", style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
        }
    }
}

/**
 * 折叠态的工具调用：一行「✓ 已完成 · bash」，点开才看参数和输出。
 *
 * 对话里工具调用是高频且低信息量的——用户真正关心的是「它做了什么、成不成」。
 * 每次都摊成一张带边框的卡片，一屏就只能放两条消息了。等用户处理（审批 / 回答）
 * 的那种调用仍然用 [ToolCallCard]：那需要显眼的边框和按钮。
 */
@Composable
fun ToolSummaryLine(row: ToolRow, loadImage: suspend (MessageImage) -> ByteArray, modifier: Modifier = Modifier) {
    val p = LocalRuyiPalette.current
    var expanded by remember(row.key) { mutableStateOf(false) }
    val (statusText, statusColor) = when (row.phase) {
        ToolPhase.Running -> "执行中" to p.textSecondary
        ToolPhase.Succeeded -> "已完成" to p.textSecondary
        ToolPhase.Failed -> "执行失败" to p.danger
        ToolPhase.Interrupted -> "已中断" to p.textTertiary
    }
    // 默认图标指向右；折叠时转 90° 朝下，展开时转 270° 朝上。转 180° 会指向左边，
    // 看着像「返回上一级」而不是「收起」。
    val chevron by animateFloatAsState(if (expanded) 270f else 90f, label = "chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起工具详情" else "展开工具详情",
                ) { expanded = !expanded }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.phase == ToolPhase.Failed) {
                GlyphClose(size = 15.dp, tint = p.danger)
            } else {
                GlyphCheckCircle(size = 15.dp, tint = statusColor)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                "$statusText · ${row.name}",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                color = p.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.rotate(chevron)) { GlyphChevron(size = 13.dp, tint = p.textTertiary) }
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.chip)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 参数里通常已经有 description，再单独摆一行摘要就是同一句话说两遍。
                if (row.arguments.isBlank() && row.summary.isNotBlank()) {
                    Text(row.summary, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                }
                if (row.arguments.isNotBlank()) ToolArguments(row.arguments)
                row.images.takeIf { it.isNotEmpty() }?.let { ToolImages(it, loadImage) }
                row.output?.takeIf { it.isNotBlank() }?.let { ToolDetail("结果", it) }
                row.error?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger)
                }
                if (row.output?.isEmpty() == true) {
                    Text("工具已返回，无文本输出", style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
                }
            }
        }
    }
}

@Composable
fun ToolApprovalCard(
    approval: ToolApproval,
    tool: ToolRow? = null,
    enabled: Boolean = true,
    onRespond: (String, ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.accent, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("需要批准 · ${approval.toolName}", style = MaterialTheme.typography.titleSmall, color = p.text)
        approval.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary) }
        tool?.arguments?.takeIf { it.isNotBlank() }?.let { ToolArguments(it, label = "本次操作") }
        Text("仅授权本次操作，不会自动批准后续请求。", style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
        approval.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onRespond(approval.eventId, ApprovalDecision.Reject) }, enabled = enabled && !approval.submitting, modifier = Modifier.weight(1f)) { Text("拒绝") }
            Button(onClick = { onRespond(approval.eventId, ApprovalDecision.AllowOnce) }, enabled = enabled && !approval.submitting, modifier = Modifier.weight(1f)) { Text(if (approval.submitting) "提交中…" else "允许本次") }
        }
    }
}

@Composable
private fun ToolArguments(raw: String, label: String = "参数") {    val fields = remember(raw) { argumentFields(raw) }
    if (fields == null) ToolDetail(label, raw) else ToolFields(label, fields)
}

/**
 * 工具参数一般只有 command / path / description 几个短字段，摊成「键：值」比
 * 直接抛一段 JSON 好读得多。整段代码或文件内容塞进键值对反而更难读，那种情况
 * 仍然原样等宽展示，所以这里返回 null 让调用方回落。
 */
private fun argumentFields(raw: String): List<Pair<String, String>>? {
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    if (obj.isEmpty() || obj.size > 8) return null
    val fields = obj.entries.map { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return null
        key to primitive.content
    }
    return fields.takeIf { list -> list.none { it.second.length > 400 || it.second.count { c -> c == '\n' } > 6 } }
}

@Composable
private fun ToolFields(label: String, fields: List<Pair<String, String>>) {
    val p = LocalRuyiPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        Column(
            Modifier.fillMaxWidth().background(p.cardPressed, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            fields.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        // 协议键名是 snake_case，直接显示会在窄列里从单词中间折断；
                        // 换成空格后可以自然换行，读起来也更像标签。
                        key.replace('_', ' '),
                        modifier = Modifier.width(76.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = p.textTertiary,
                    )
                    SelectionContainer {
                        Text(
                            value.ifBlank { "—" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            color = p.text,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolDetail(label: String, value: String) {
    val p = LocalRuyiPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        SelectionContainer {
            Text(
                value,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).background(p.cardPressed, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                color = p.text,
            )
        }
    }
}

/** 工具成功结果里的图片（桌面生图 / read_image）。与消息图片同源，走同一加载通道。 */
@Composable
private fun ToolImages(images: List<MessageImage>, loadImage: suspend (MessageImage) -> ByteArray) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("图片（${images.size}）", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        images.forEach { image ->
            AttachmentImagePreview(
                image = image,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, p.hairline, shape),
                load = loadImage,
            )
        }
    }
}

/**
 * 模型反问用户的交互卡。工具调用会一直停在这里等人回答，所以这张卡必须
 * 自己把「在等我」说清楚，并给出可点的选项，而不是依赖用户去猜。
 */
@Composable
fun QuestionCard(
    pending: PendingQuestions,
    enabled: Boolean = true,
    onToggle: (questionId: String, label: String) -> Unit,
    onDraft: (questionId: String, text: String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.accent, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("需要你的回答", style = MaterialTheme.typography.labelSmall, color = p.accent)

        pending.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (question.header != null) {
                    Text(question.header, style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
                }
                Text(question.question, style = MaterialTheme.typography.bodyMedium, color = p.text)
                question.options.forEach { option ->
                    OptionRow(
                        option = option,
                        selected = pending.selected[question.id]?.contains(option.label) == true,
                        multi = question.multiSelect,
                        enabled = enabled && !pending.submitting,
                        onClick = { onToggle(question.id, option.label) },
                    )
                }
                AnswerField(
                    value = pending.drafts[question.id].orEmpty(),
                    placeholder = if (question.options.isEmpty()) "输入你的回答" else "其他补充（可选）",
                    enabled = enabled && !pending.submitting,
                    onValueChange = { onDraft(question.id, it) },
                )
            }
        }

        pending.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSkip,
                enabled = enabled && !pending.submitting,
                modifier = Modifier.weight(1f),
            ) { Text("跳过") }
            Button(
                onClick = onSubmit,
                enabled = enabled && !pending.submitting && pending.answered,
                modifier = Modifier.weight(1f),
            ) { Text(if (pending.submitting) "提交中…" else "提交") }
        }
    }
}

@Composable
private fun OptionRow(
    option: AskOption,
    selected: Boolean,
    multi: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) p.accent.copy(alpha = 0.10f) else p.cardPressed, shape)
            .border(1.dp, if (selected) p.accent else Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(if (multi) RoundedCornerShape(5.dp) else CircleShape)
                .border(
                    1.5.dp,
                    if (selected) p.accent else p.textTertiary,
                    if (multi) RoundedCornerShape(5.dp) else CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(if (multi) RoundedCornerShape(2.dp) else CircleShape)
                        .background(p.accent),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) p.accent else p.text,
            )
            option.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
        }
    }
}

@Composable
private fun AnswerField(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text),
        cursorBrush = SolidColor(p.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.cardPressed, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = p.textTertiary)
                }
                inner()
            }
        },
    )
}
