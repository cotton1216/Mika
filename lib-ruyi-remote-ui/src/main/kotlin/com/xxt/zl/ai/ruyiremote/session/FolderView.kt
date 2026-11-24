package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 某台主机的文件夹视图：有序文件夹列表 + 排序方式。对应 dsh-Go 的 `DrawerView`。
 *
 * 单独成一层（而不是把 folders 直接塞进 Store）是为了让「文件夹集合」与「存储后端」
 * 解耦：Store 只负责按 namespace 读写一个 [FolderView]，视图自身负责派生关系与
 * 一致性约束。
 */
data class FolderView(
    val folders: List<RemoteFolder> = emptyList(),
    val sort: FolderSort = FolderSort.ACTIVITY,
) {
    /** sessionId → folderId。一个会话至多属于一个文件夹；脏数据下以最后一个文件夹为准。 */
    val folderOf: Map<String, String>
        get() = buildMap {
            folders.forEach { folder -> folder.sessionIds.forEach { put(it, folder.id) } }
        }

    fun byId(id: String?): RemoteFolder? = id?.let { key -> folders.firstOrNull { it.id == key } }
}

/** 抽屉排序方式，对齐 dsh-Go `DrawerSort`。 */
enum class FolderSort {
    /** 最近活动优先（主机的 updatedAt）。 */
    ACTIVITY,

    /** 会话创建时间优先。 */
    CREATED,
    ;
}

/** 一致性写操作：把 [sessionId] 归入 [folderId]，并先从其他文件夹移除（保证唯一归属）。 */
fun FolderView.assign(folderId: String, sessionId: String): FolderView = copy(
    folders = folders.map { folder ->
        val without = folder.sessionIds.filterNot { it == sessionId }
        if (folder.id == folderId) {
            // 已在目标文件夹且位于最前则原样返回，避免无谓重建。
            if (folder.sessionIds.firstOrNull() == sessionId) folder
            else folder.copy(sessionIds = listOf(sessionId) + without)
        } else if (without.size != folder.sessionIds.size) {
            folder.copy(sessionIds = without)
        } else {
            folder
        }
    },
)

/** 一致性写操作：把 [sessionId] 从所有文件夹移出。 */
fun FolderView.unassign(sessionId: String): FolderView = copy(
    folders = folders.map { folder ->
        val without = folder.sessionIds.filterNot { it == sessionId }
        if (without.size == folder.sessionIds.size) folder else folder.copy(sessionIds = without)
    },
)

// ---- FolderView ↔ JSON ----
// internal 以便单测直接覆盖 round-trip。

internal fun encodeFolderView(view: FolderView): String = buildJsonObject {
    put("folders", JsonArray(view.folders.map { it.toJson() }))
    put("sort", view.sort.name)
}.toString()

internal fun decodeFolderView(raw: String): FolderView {
    val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return FolderView()
    val seen = HashSet<String>()
    val folders = obj.arr("folders")
        .mapNotNull { (it as? JsonObject)?.toFolder() }
        .filter { seen.add(it.id) } // 文件夹 id 去重，与 dsh-Go DrawerView.fromJson 一致
    val sort = when (obj.str("sort")) {
        FolderSort.CREATED.name -> FolderSort.CREATED
        else -> FolderSort.ACTIVITY
    }
    return FolderView(folders = folders, sort = sort)
}
