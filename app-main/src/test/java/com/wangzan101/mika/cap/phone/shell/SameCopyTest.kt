package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主屏占用、Mika 别的屏占用、冷启动看的任务:装了两份的 App 按「包名 + 用户」分开算,只装一份的照旧。 */
class SameCopyTest {
    private val chat = "com.example.chat"

    @Test fun theUserOnTheMainCopyDoesNotBlockTheCloneAndViceVersa() {
        // 主屏上是用户 0 的那份
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf(chat), 0, chat, 128))
        // 主屏上是分身那份
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, 128))
        assertFalse(ShellService.sameCopy(listOf(chat), 128, chat, 0))
    }

    @Test fun unknownTaskUserCountsAsTheSameCopy() {
        // 读不到任务属于哪份用户:宁可当成占用
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 128))
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 0))
    }

    @Test fun singleCopyAndPackageOnlyChecksAreUnchanged() {
        // 只装了一份:任务都在用户 0,和原来一样按包名算
        assertTrue(ShellService.sameCopy(listOf("x.launcher", chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf("x.other"), 0, chat, 0))
        // 不分哪一份(分享):任何一份都算
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, null))
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, null))
    }
}
