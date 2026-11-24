package com.wangzan101.mika.core.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RunInboxTest {
    @Test fun withdrawingOneMessageLeavesTheOthersInOrder() {
        val inbox = RunInbox()
        inbox.steer("a", "改这句")
        inbox.steer("b", "留下这句")
        assertTrue(inbox.withdrawSteer("a"))
        assertFalse(inbox.withdrawSteer("a"))
        assertEquals(listOf(RunInbox.UserSteer("b", "留下这句")), inbox.takeSteers())
    }

    @Test fun withdrawingTheLastMessageClearsTheWakeup() {
        val inbox = RunInbox()
        inbox.steer("a", "撤回")
        assertTrue(inbox.withdrawSteer("a"))
        assertFalse(inbox.hasSteers())
        assertTrue(inbox.redirects.tryReceive().isFailure)
        assertTrue(inbox.close().isEmpty())
    }

    @Test fun consumedOrClosedMessagesCannotBeWithdrawn() {
        val consumed = RunInbox()
        consumed.steer("a", "已经交给模型")
        assertEquals(1, consumed.takeSteers().size)
        assertFalse(consumed.withdrawSteer("a"))
        val closed = RunInbox()
        closed.steer("b", "留给下一任务")
        closed.close()
        assertFalse(closed.withdrawSteer("b"))
        assertEquals(listOf(RunInbox.UserSteer("b", "留给下一任务")), closed.handoffUnconsumed())
    }

    // 电脑追加的只归当前任务,撤不撤由电脑定:手机这边撤不回,它照样交给模型
    @Test fun remoteSteersCannotBeWithdrawn() {
        val inbox = RunInbox()
        inbox.steerCurrent("r", "电脑追加")
        inbox.steer("p", "手机插话")
        assertFalse(inbox.withdrawSteer("r"))
        assertTrue(inbox.withdrawSteer("p"))
        assertTrue(inbox.hasSteers())
        assertEquals(listOf(RunInbox.UserSteer("r", "电脑追加", currentTaskOnly = true)), inbox.takeSteers())
    }

    @Test fun withdrawalAndModelPickupHaveOnlyOneWinner() {
        repeat(100) {
            val inbox = RunInbox()
            inbox.steer("a", "原话")
            val withdrawn = AtomicBoolean()
            val editor = Thread { withdrawn.set(inbox.withdrawSteer("a")) }
            editor.start()
            val pickedUp = inbox.takeSteers()
            editor.join()
            assertEquals(1, (if (withdrawn.get()) 1 else 0) + pickedUp.size)
        }
    }
}
