package com.wangzan101.mika.core.run

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class RemoteSteerInboxTest {
    @Test fun currentTaskSteerWakesTheSameInboxAndPreservesItsScope() = runBlocking {
        val inbox = RunInbox()
        assertEquals(InputReceipt.Accepted, inbox.steerCurrent("remote", "只看今天\n不要发送"))
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("ui", "also read yesterday")))
        assertTrue(inbox.signal.pending.value)
        assertTrue(inbox.redirects.tryReceive().isSuccess)
        val messages = inbox.takeSteers()
        assertEquals(listOf("remote", "ui"), messages.map { it.messageId })
        assertEquals(listOf(true, false), messages.map { it.currentTaskOnly })
        assertEquals("只看今天\n不要发送", messages.first().text)
        assertFalse(inbox.signal.pending.value)
        assertTrue(inbox.closeUnlessSteered())
        assertEquals(InputReceipt.Closed, inbox.steerCurrent("late", "more"))
        assertTrue(inbox.handoffUnconsumed().isEmpty())
        // 手机原有的续聊行为不变。
        assertEquals(InputReceipt.NextTask, inbox.send(RunInput.Steer("next", "next task")))
    }

    @Test fun cancellationRejectsRemoteInstructionsEvenBeforeTheLoopCloses() {
        val inbox = RunInbox()
        inbox.cancel()
        assertEquals(InputReceipt.Closed, inbox.steerCurrent("late", "continue"))
        assertFalse(inbox.hasSteers())
        assertTrue(inbox.handoffUnconsumed().isEmpty())
    }

    @Test fun unconsumedRemoteMessagesRemainMarkedAsCurrentTaskOnly() = runBlocking {
        val inbox = RunInbox()
        inbox.steerCurrent("remote", "more")
        inbox.send(RunInput.Steer("ui", "next"))
        val left = inbox.handoffUnconsumed()
        assertEquals(listOf("ui"), left.filterNot { it.currentTaskOnly }.map { it.messageId })
        assertEquals(listOf("remote"), left.filter { it.currentTaskOnly }.map { it.messageId })
        assertEquals(InputReceipt.Closed, inbox.steerCurrent("too-late", "more"))
        assertTrue(inbox.handoffUnconsumed().isEmpty())
    }

    @Test fun deliveryAndFinalCloseHaveOneWinner() {
        repeat(100) {
            val inbox = RunInbox()
            val barrier = CyclicBarrier(2)
            var receipt: InputReceipt? = null
            var closed = false
            val writer = thread { barrier.await(); receipt = inbox.steerCurrent("m", "more") }
            val finisher = thread { barrier.await(); closed = inbox.closeUnlessSteered() }
            writer.join(); finisher.join()
            if (closed) {
                assertEquals(InputReceipt.Closed, receipt)
                assertTrue(inbox.handoffUnconsumed().isEmpty())
            } else {
                assertEquals(InputReceipt.Accepted, receipt)
                assertEquals(listOf("m"), inbox.takeSteers().map { it.messageId })
            }
        }
    }
}
