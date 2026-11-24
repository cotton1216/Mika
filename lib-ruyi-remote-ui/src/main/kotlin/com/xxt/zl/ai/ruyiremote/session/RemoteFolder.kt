package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 用户自建的会话文件夹。与 dsh-Go 的 `DrawerFolder` 一一对齐（六字段全对齐）。
 *
 * - [tint]：身份色板索引（0..[FOLDER_MARK_TINTS]-1），null 表示不着色；
 * - [emoji]：文件夹图标，空表示用默认文件夹图标；
 * - [collapsed]：折叠态，落盘以便重启后保持；
 * - [sessionIds]：成员会话 id，最近加入的在前。
 *
 * 这是纯本地元数据：服务端会话接口没有承载文件夹的字段，因此不参与任何协议
 * 收发，只走 [FolderStore] 落本机 SharedPreferences。一个会话至多属于一个文件夹
 * （唯一性由 [FolderView] 的写入侧维护）。
 */
data class RemoteFolder(
    val id: String,
    val name: String,
    val tint: Int? = null,
    val emoji: String? = null,
    val collapsed: Boolean = false,
    val sessionIds: List<String> = emptyList(),
)

/** 文件夹可选的着色数量，与 dsh-Go `DrawerFolder.markTints` 对齐。 */
internal const val FOLDER_MARK_TINTS = 8

// ---- RemoteFolder ↔ JSON ----
// internal 以便单测直接覆盖 round-trip；真机走 SharedPrefsFolderStore。

internal fun RemoteFolder.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    tint?.let { put("tint", it) }
    emoji?.let { put("emoji", it) }
    if (collapsed) put("collapsed", true)
    put("sessions", JsonArray(sessionIds.map(::JsonPrimitive)))
}

/** 解析单个文件夹；结构非法（缺 id/name、着色越界）时返回 null 由调用方丢弃。 */
internal fun JsonObject.toFolder(): RemoteFolder? {
    val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
    val name = str("name")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val tint = num("tint")?.toInt()?.takeIf { it in 0 until FOLDER_MARK_TINTS }
    val emoji = str("emoji")?.takeIf { it.isNotBlank() }
    val collapsed = bool("collapsed") == true
    // 成员去重，避免手工改过的脏数据让同一会话在文件夹里出现多次。
    val sessionIds = arr("sessions")
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .distinct()
    return RemoteFolder(
        id = id,
        name = name,
        tint = tint,
        emoji = emoji,
        collapsed = collapsed,
        sessionIds = sessionIds,
    )
}
