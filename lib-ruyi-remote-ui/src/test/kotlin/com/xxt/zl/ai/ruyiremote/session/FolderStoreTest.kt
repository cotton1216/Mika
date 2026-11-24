package com.xxt.zl.ai.ruyiremote.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据层单测：序列化 round-trip、namespace 隔离、CRUD、去重、唯一归属。 */
class FolderStoreTest {

    private val folderA = RemoteFolder(
        id = "f1",
        name = "工作",
        tint = 2,
        emoji = "💼",
        collapsed = true,
        sessionIds = listOf("s1", "s2"),
    )
    private val folderB = RemoteFolder(
        id = "f2",
        name = "阅读",
        sessionIds = listOf("s3"),
    )
    private val view = FolderView(folders = listOf(folderA, folderB), sort = FolderSort.CREATED)

    @Test
    fun `folder view round-trip preserves all six fields and sort`() {
        val decoded = decodeFolderView(encodeFolderView(view))
        assertEquals(view, decoded)
        assertEquals(FolderSort.CREATED, decoded.sort)
        assertEquals(2, decoded.folders[0].tint)
        assertEquals("💼", decoded.folders[0].emoji)
        assertTrue(decoded.folders[0].collapsed)
        assertEquals(listOf("s1", "s2"), decoded.folders[0].sessionIds)
    }

    @Test
    fun `decode dedups folder ids and session ids`() {
        val raw = """
            {"folders":[
              {"id":"f1","name":"A","sessions":["s1","s1","s2"]},
              {"id":"f1","name":"B","sessions":["s3"]}
            ],"sort":"activity"}
        """.trimIndent()
        val decoded = decodeFolderView(raw)
        assertEquals(1, decoded.folders.size)
        assertEquals("A", decoded.folders[0].name)
        assertEquals(listOf("s1", "s2"), decoded.folders[0].sessionIds)
    }

    @Test
    fun `assign moves a session to a single folder and unassign removes it everywhere`() {
        val moved = view.assign("f2", "s1")
        assertEquals(listOf("s2"), moved.byId("f1")?.sessionIds)
        assertEquals(listOf("s1", "s3"), moved.byId("f2")?.sessionIds)
        assertEquals("f2", moved.folderOf["s1"])

        val removed = moved.unassign("s1")
        assertNull(removed.folderOf["s1"])
        assertEquals(listOf("s3"), removed.byId("f2")?.sessionIds)
    }

    @Test
    fun `namespace isolates views and write-read round-trips`() = runTest {
        val store = InMemoryFolderStore()
        store.write("host-a", view)
        store.write("host-b", FolderView(folders = listOf(folderB)))

        assertEquals(view, store.read("host-a"))
        assertEquals(listOf(folderB), store.read("host-b")?.folders)
        assertNull(store.read("host-c"))
    }

    @Test
    fun `malformed json decodes to empty view instead of throwing`() {
        assertEquals(FolderView(), decodeFolderView("not-json"))
        assertEquals(FolderView(), decodeFolderView("{}"))
    }
}
