package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Test

class SteerJoinTest {
    private val notes = listOf(
        SteerNote("m1", "改成查明天", SteerState.Received),
        SteerNote("m2", "顺便看看后天", SteerState.Received),
    )

    // 一批两句插话一起交给模型:打断的那段字只挂在第一句上,第二句没有
    @Test fun interruptedReplyGoesToTheFirstSteerOfTheBatch() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf(SteerState.Joined, SteerState.Joined), after.map { it.state })
        assertEquals(listOf("今天晴", null), after.map { it.interrupted })
    }

    // 没有打断什么时只改去向
    @Test fun steerWithoutInterruptionOnlyChangesState() {
        val after = SteerJoin().joined(notes, "m2")
        assertEquals(listOf(SteerState.Received, SteerState.Joined), after.map { it.state })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    // 用过即清:下一批插话不会再挂上上一次的那段字
    @Test fun interruptedReplyIsUsedOnce() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val first = join.joined(notes, "m1")
        val second = join.joined(first + SteerNote("m3", "算了", SteerState.Received), "m3")
        assertEquals(listOf("今天晴", null, null), second.map { it.interrupted })
    }

    // 插话之前已经说完的回复同样挂到这一批的第一句上,和被打断的分开记
    @Test fun finishedReplyGoesToTheFirstSteerAndIsUsedOnce() {
        val join = SteerJoin()
        join.finished("光合作用是……的过程。")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf("光合作用是……的过程。", null), after.map { it.finished })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    @Test fun remoteStatusExcludesLocalMessagesAndDistinguishesReceiptConsumptionAndDiscard() {
        val local = SteerNote("local", "phone-only text", SteerState.Joined)
        val remote = SteerNote("remote", "only today", SteerState.Received, remote = true)
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "received")), remoteSteers(listOf(local, remote)))
        val joined = SteerJoin().joined(listOf(local, remote), "remote")
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "joined")), remoteSteers(joined))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(remote.copy(state = SteerState.Cancelled))))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "joined")),
            remoteSteers(joined, finished = true))
    }

    // 远程插话本来撤不回;万一是撤回状态也只报没用上,不多出一种对外状态;手机上撤回的不报给电脑
    @Test fun withdrawnMapsToNotAppliedAndLocalWithdrawalsStayHidden() {
        val remote = SteerNote("remote", "only today", SteerState.Withdrawn, remote = true)
        val local = SteerNote("local", "phone-only text", SteerState.Withdrawn)
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")), remoteSteers(listOf(local, remote)))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
    }
}
