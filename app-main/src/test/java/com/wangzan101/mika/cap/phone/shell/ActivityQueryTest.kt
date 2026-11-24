package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityQueryTest {
    @Test
    fun argsCarryOnlyWhatIsGiven() {
        assertEquals(
            listOf("cmd", "package", "query-activities", "--brief", "--user", "current", "-a", "android.intent.action.SEND", "-t", "text/plain", "-p", "a.b"),
            ActivityQuery.args("android.intent.action.SEND", type = "text/plain", packageName = "a.b"),
        )
        // 另一份用户里的启动页:查那份用户
        assertEquals(
            listOf("cmd", "package", "query-activities", "--brief", "--user", "128", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", "a.b"),
            ActivityQuery.args("android.intent.action.MAIN", category = "android.intent.category.LAUNCHER", packageName = "a.b", user = "128"),
        )
    }

    @Test
    fun parsesComponentsAndExpandsShortNames() {
        val out = """
            2 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x608000 specificIndex=-1 isDefault=true
                a.b/.ui.ShareUI
              Activity #1:
                priority=0 preferredOrder=0 match=0x608000 specificIndex=-1 isDefault=true
                a.b/c.d.SaveUI
        """.trimIndent()
        assertEquals(listOf("a.b" to "a.b.ui.ShareUI", "a.b" to "c.d.SaveUI"), ActivityQuery.parse(out))
    }

    @Test
    fun nothingFoundIsEmpty() {
        assertEquals(emptyList<Pair<String, String>>(), ActivityQuery.parse("No activities found"))
    }
}
