package com.xxt.zl.ai.ruyiremote.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件夹视图的本地持久化。
 *
 * [namespace] 由调用方用 [sessionSnapshotNamespace] 按「中继 + 主机」生成，Store 自身
 * 不关心隔离口径——这样它与快照存储共用同一套隔离函数，却落在独立 prefs 文件里，
 * 不会与会话快照的键冲突。
 */
interface FolderStore {
    suspend fun read(namespace: String): FolderView?
    suspend fun write(namespace: String, view: FolderView)
}

/** 单测用：纯内存，进程退出即失；多协程并发读写用并发容器。 */
class InMemoryFolderStore : FolderStore {
    private val cache = ConcurrentHashMap<String, FolderView>()

    override suspend fun read(namespace: String): FolderView? = cache[namespace]

    override suspend fun write(namespace: String, view: FolderView) {
        cache[namespace] = view
    }
}

/** 真机：SharedPreferences 存 FolderView JSON。非敏感元数据，不走 Keystore 加密。 */
class SharedPrefsFolderStore(context: Context) : FolderStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("dsh_remote_folder", Context.MODE_PRIVATE)

    override suspend fun read(namespace: String): FolderView? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(namespace, null) ?: return@withContext null
        runCatching { decodeFolderView(raw) }.getOrNull()
    }

    override suspend fun write(namespace: String, view: FolderView) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(namespace, encodeFolderView(view)).apply()
        }
    }
}
