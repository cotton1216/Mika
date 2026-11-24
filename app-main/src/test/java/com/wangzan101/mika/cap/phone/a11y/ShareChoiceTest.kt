package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.settings.AppAccessStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 主动分享浮层的三个选择只按约定动权限:取消不动、只分享不解除禁用、允许此应用清掉分享许可。 */
class ShareChoiceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = AppAccessStore(java.io.File(temp.root, "access.json"))

    @Test fun allowingShareOnlyKeepsTheAppBlocked() {
        val store = store()
        store.setBlocked("app", true)
        assertTrue(applyShareChoice(1, "app", store))
        assertTrue(store.current().canShare("app"))
        assertEquals("app_blocked", store.current().denial("app")?.code)
    }

    @Test fun removingFromBlocklistRestoresCallsAndDropsTheShareGrant() {
        val store = store()
        store.setBlocked("app", true)
        store.setShareAllowed("app", true)
        assertTrue(applyShareChoice(2, "app", store))
        assertNull(store.current().denial("app"))
        // 独立分享许可被清掉:以后它是普通应用,不再需要单独授权
        assertTrue(store.current().shareAllowed.isEmpty())
        assertTrue(store.current().canShare("app"))
    }

    @Test fun cancellingChangesNothing() {
        val store = store()
        store.setBlocked("app", true)
        val before = store.current()
        assertFalse(applyShareChoice(0, "app", store))
        assertFalse(applyShareChoice(7, "app", store))
        assertEquals(before.blocked, store.current().blocked)
        assertEquals(before.shareAllowed, store.current().shareAllowed)
        assertEquals(before.revision, store.current().revision)
    }
}
