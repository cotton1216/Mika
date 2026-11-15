package com.xxt.zl.ai.ruyiremote.ui.components

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xxt.zl.ai.ruyiremote.session.*
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/** 系统文档选择器只授予所选 content URI；不请求存储目录权限。 */
@Composable
fun AttachmentPicker(
    enabled: Boolean,
    onImage: (PromptImage) -> Unit,
    onFile: (SelectedFile) -> Unit,
    modifier: Modifier = Modifier,
    fileUploadEnabled: Boolean = true,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentImage by rememberUpdatedState(onImage)
    val currentFile by rememberUpdatedState(onFile)
    fun read(uri: Uri?, image: Boolean) {
        if (uri == null || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { readPickedFile(resolver, uri, if (image) MAX_PROMPT_IMAGE_BYTES else MAX_FILE_BYTES) }
                if (image) {
                    val selected = withContext(Dispatchers.Default) {
                        val mediaType = imageMediaType(file.bytes) ?: throw AttachmentException("请选择 PNG、JPEG、WebP 或 GIF 图片")
                        checkImageBounds(file.bytes)
                        PromptImage(file.name, mediaType, Base64.getEncoder().encodeToString(file.bytes), file.bytes.size)
                    }
                    currentImage(selected)
                } else currentFile(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "无法读取附件，请重新选择"
            } finally {
                busy = false
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, true) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, false) }
    Column(modifier) {
        // 这两个入口是「偶尔用一次」的动作，做成两颗白胶囊跟输入栏同一套语言；
        // 之前是裸 TextButton，浮在渐变底上只是两行紫色文字，看着像没排完版。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PickerChip("添加图片", enabled && !busy, icon = { tint -> GlyphImage(size = 15.dp, tint = tint) }) {
                imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif"))
            }
            PickerChip("上传文件", enabled && !busy && fileUploadEnabled, icon = { tint -> GlyphFile(size = 15.dp, tint = tint) }) {
                filePicker.launch(arrayOf("*/*"))
            }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (!fileUploadEnabled) Text("此 Host 暂不支持工作区文件上传", style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PickerChip(
    label: String,
    enabled: Boolean,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(p.float.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(if (enabled) p.textSecondary else p.textTertiary)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) p.textSecondary else p.textTertiary,
        )
    }
}

private fun readPickedFile(resolver: ContentResolver, uri: Uri, limit: Int): SelectedFile {
    if (uri.scheme != "content") throw AttachmentException("仅支持系统选择器授予的文件")
    var name = "附件"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > limit) {
                throw AttachmentException("附件太大，当前上限为 ${limit / (1024 * 1024)} MiB")
            }
        }
    }
    if (!validAttachmentName(name)) throw AttachmentException("文件名不受支持，请重命名后选择")
    val bytes = resolver.openInputStream(uri)?.use { readAttachmentBytes(it, limit) }
        ?: throw AttachmentException("无法读取所选文件")
    return SelectedFile(name, bytes)
}

private fun checkImageBounds(bytes: ByteArray): BitmapFactory.Options {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 8192 || options.outHeight > 8192 ||
        options.outWidth.toLong() * options.outHeight > 16_000_000) throw AttachmentException("图片无法解码或尺寸过大（上限 8192 边长、1600 万像素）")
    return options
}

/** load 只能接会话附件 RPC；组件本身没有 HTTP、Cookie 或令牌访问能力。 */
@Composable
fun AttachmentImagePreview(
    image: MessageImage,
    modifier: Modifier = Modifier,
    load: suspend (MessageImage) -> ByteArray = { decodeImageData(it.data ?: throw AttachmentException("缺少会话图片读取入口")) },
) {
    var bitmap by remember(image) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(image) { mutableStateOf<String?>(null) }
    var expanded by remember(image) { mutableStateOf(false) }
    var attempt by remember(image) { mutableIntStateOf(0) }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(image, attempt) {
        error = null
        try {
            val bytes = currentLoad(image)
            bitmap = withContext(Dispatchers.Default) {
                if (bytes.size > MAX_PREVIEW_IMAGE_BYTES) throw AttachmentException("图片超过预览大小限制")
                val bounds = checkImageBounds(bytes)
                val options = BitmapFactory.Options()
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1600) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                    ?: throw AttachmentException("图片预览解码失败")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "图片读取失败"
        }
    }
    Column(modifier) {
        val displayed = bitmap
        when {
            // 按图片自身比例占位：ContentScale.Fit + 固定宽度会在两侧留白条，
            // 看起来像一张「白卡片中间贴了小图」，聊天里非常怪。
            displayed != null -> {
                val ratio = displayed.width.toFloat() / displayed.height.toFloat()
                Image(
                    displayed,
                    image.name,
                    Modifier
                        .fillMaxWidth(if (ratio >= 1f) 1f else ratio.coerceIn(0.5f, 1f))
                        .aspectRatio(ratio)
                        .clickable { expanded = true },
                    contentScale = ContentScale.FillWidth,
                )
            }
            error != null -> {
                Text("${image.name}：$error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { attempt++ }) { Text("重试图片") }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在读取${image.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded && displayed != null) Dialog(onDismissRequest = { expanded = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp)) {
                    Text(image.name, style = MaterialTheme.typography.titleSmall)
                    Image(displayed, image.name, Modifier.fillMaxWidth().heightIn(max = 560.dp), contentScale = ContentScale.Fit)
                    TextButton(onClick = { expanded = false }) { Text("关闭") }
                }
            }
        }
    }
}
