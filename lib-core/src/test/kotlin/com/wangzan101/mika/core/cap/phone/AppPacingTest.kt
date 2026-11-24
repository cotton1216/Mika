package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.PlacePace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * App 里的节奏:同一屏短停、新的一屏长停,限制浏览快慢的 App 按字数加读的时间、停得更久,都有上限;
 * 模型花掉的时间算在内;屏数上限只给限制浏览快慢的 App,用户答话后重来,做成对外动作后不再设;「快」从不等。
 */
class AppPacingTest {
    private val std = AppPacing.Limits()
    private val careful = AppPacing.Limits.CAREFUL

    @Test fun firstActionDoesNotWait() {
        assertEquals(0, AppPacing.human(Random(1)).waitBefore(0, 500))
    }

    @Test fun sameScreenAndNewScreenUseTheirRanges() {
        repeat(100) { seed ->
            val p = AppPacing.human(Random(seed))
            p.noteAction("com.a", 1_000, newScreen = false)
            val same = p.waitBefore(1_000)
            assertTrue("$same", same in std.sameScreenMs.first..std.maxDwellMs)
            if (same > std.sameScreenMs.last) assertTrue(same <= std.sameScreenMs.last + std.pauseMs.last)
            p.noteAction("com.a", 2_000, newScreen = true)
            val fresh = p.waitBefore(2_000, textChars = 5_000)
            // 标准的范围不按字数加读的时间
            assertTrue("$fresh", fresh in std.newScreenMs.first..std.maxDwellMs)
        }
        // 平均来说新的一屏停得比同一屏久
        fun mean(newScreen: Boolean) = (0 until 200).map { s ->
            AppPacing.human(Random(s)).apply { noteAction("com.a", 0, newScreen) }.waitBefore(0)
        }.average()
        assertTrue(mean(false) < mean(true))
    }

    @Test fun standardDwellIsModest() {
        repeat(200) { seed ->
            val p = AppPacing.human(Random(seed))
            p.noteAction("com.a", 0, newScreen = seed % 2 == 0)
            assertTrue(p.waitBefore(0) in 500L..3_500L)
        }
    }

    @Test fun carefulAppsReadInProportionToTheContentWithBounds() {
        val p = AppPacing.human(Random(4))
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL)
        val empty = p.waitBefore(0, textChars = 0)
        assertTrue("$empty", empty in careful.newScreenMs.first..careful.newScreenMs.last + careful.pauseMs.last)
        // 每个字加 readMsPerChar,读的时间至多 maxReadMs,整个停留至多 maxDwellMs
        assertEquals(empty + 100 * careful.readMsPerChar, p.waitBefore(0, textChars = 100))
        assertEquals((empty + careful.maxReadMs).coerceAtMost(careful.maxDwellMs), p.waitBefore(0, textChars = 1_000_000))
        // 同一屏上接着做不加读的时间
        p.noteAction("com.slow", 0, newScreen = false, pace = PlacePace.CAREFUL)
        val same = p.waitBefore(0, textChars = 1_000)
        assertTrue("$same", same in careful.sameScreenMs.first..careful.sameScreenMs.last + careful.pauseMs.last)
    }

    @Test fun timeAlreadySpentCounts() {
        val p = AppPacing.human(Random(2))
        p.noteAction("com.a", 10_000, newScreen = true)
        val full = p.waitBefore(10_000)
        assertEquals(full - 400, p.waitBefore(10_400))
        assertEquals(0, p.waitBefore(10_000 + full))
        assertEquals(0, p.waitBefore(60_000))
    }

    @Test fun idleScrollOnlyOnNewCarefulScreens() {
        val p = AppPacing.human(Random(2))
        p.noteAction("com.a", 0, newScreen = true)
        assertFalse(p.idleAllowed())
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL)
        assertTrue(p.idleAllowed())
        p.noteAction("com.slow", 0, newScreen = false, pace = PlacePace.CAREFUL)
        assertFalse(p.idleAllowed())
    }

    @Test fun carefulAppsHaveAScreenBudgetThatResetsWhenTheUserAnswers() {
        val p = AppPacing(Random(1), screenCap = 3)
        repeat(3) { p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL) }
        // 同一屏上的动作、往下滚读余下的内容不算
        p.noteAction("com.slow", 0, newScreen = false, pace = PlacePace.CAREFUL)
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL, counts = false)
        assertEquals(3, p.screensIn("com.slow"))
        assertTrue(p.capReached("com.slow", PlacePace.CAREFUL))
        // 普通 App 没有上限
        repeat(10) { p.noteAction("com.a", 0, newScreen = true) }
        assertFalse(p.capReached("com.a", PlacePace.NORMAL))
        p.userAnswered()
        assertEquals(0, p.screensIn("com.slow"))
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
    }

    @Test fun anOutwardActionLiftsTheBudgetForThatApp() {
        val p = AppPacing(Random(1), screenCap = 1)
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL)
        p.noteAction("com.other", 0, newScreen = true, pace = PlacePace.CAREFUL)
        p.outwardDone("com.slow")
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
        assertTrue(p.capReached("com.other", PlacePace.CAREFUL))
        // 用户答话也不恢复
        p.userAnswered()
        repeat(3) { p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL) }
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
    }

    @Test fun offNeverWaitsNorCaps() {
        val p = AppPacing.Off
        repeat(50) { p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL) }
        assertEquals(0, p.waitBefore(0, 10_000))
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
        assertFalse(p.idleAllowed())
    }

    @Test fun settingMapsToPacing() {
        assertSame(AppPacing.Off, OperatingPace.FAST.appPacing())
        assertEquals(AppPacing.Limits(), OperatingPace.STANDARD.appPacing().limits)
        assertEquals(AppPacing.Limits.CAREFUL, OperatingPace.STANDARD.appPacing().careful)
        assertEquals(AppPacing.Limits.CAREFUL, OperatingPace.HUMAN.appPacing().limits)
        assertTrue(OperatingPace.HUMAN.appPacing().paced)

        assertSame(TypingPacing.Instant, OperatingPace.FAST.typing(PlacePace.CAREFUL))
        assertEquals(TypingPacing.Limits(), OperatingPace.STANDARD.typing(PlacePace.NORMAL).limits)
        assertEquals(TypingPacing.Limits.CAREFUL, OperatingPace.STANDARD.typing(PlacePace.CAREFUL).limits)
        assertEquals(TypingPacing.Limits.CAREFUL, OperatingPace.HUMAN.typing(PlacePace.NORMAL).limits)

        assertEquals(OperatingPace.HUMAN, OperatingPace.parse("human"))
        assertEquals(OperatingPace.FAST, OperatingPace.parse(" FAST "))
        assertEquals(null, OperatingPace.parse("quick"))
        assertEquals(null, OperatingPace.parse(null))
    }
}
