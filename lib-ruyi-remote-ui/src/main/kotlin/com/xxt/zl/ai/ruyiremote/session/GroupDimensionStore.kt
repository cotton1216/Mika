package com.xxt.zl.ai.ruyiremote.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分组维度选择的本地持久化（T13）。
 *
 * 与 [FolderStore] 同样按「中继 + 主机」namespace 隔离，但落在独立 prefs 文件
 * `dsh_remote_group_dimension`，不干扰 T11 的文件夹数据。namespace 由调用方用
 * [sessionSnapshotNamespace] 生成；Store 自身不关心隔离口径。
 *
 * 读取未命中时返回 [GroupDimension.DAY]——与 T12 既有行为一致，老用户升级后
 * 仍看到按天分组，不需要迁移。
 */
interface GroupDimensionStore {
    suspend fun read(namespace: String): GroupDimension
    suspend fun write(namespace: String, dimension: GroupDimension)
}

/** 单测用：纯内存，默认 DAY；多协程无需共享状态，写入即覆盖单值。 */
class InMemoryGroupDimensionStore : GroupDimensionStore {
    private val cache = mutableMapOf<String, GroupDimension>()

    override suspend fun read(namespace: String): GroupDimension = cache[namespace] ?: GroupDimension.DAY

    override suspend fun write(namespace: String, dimension: GroupDimension) {
        cache[namespace] = dimension
    }
}

/** 真机：SharedPreferences 存枚举 name（如 DAY/STATUS/CWD）。非敏感 UI 元数据，不走 Keystore。 */
class SharedPrefsGroupDimensionStore(context: Context) : GroupDimensionStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("dsh_remote_group_dimension", Context.MODE_PRIVATE)

    override suspend fun read(namespace: String): GroupDimension = withContext(Dispatchers.IO) {
        val raw = prefs.getString(namespace, null) ?: return@withContext GroupDimension.DAY
        runCatching { GroupDimension.valueOf(raw) }.getOrDefault(GroupDimension.DAY)
    }

    override suspend fun write(namespace: String, dimension: GroupDimension) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(namespace, dimension.name).apply()
        }
    }
}
