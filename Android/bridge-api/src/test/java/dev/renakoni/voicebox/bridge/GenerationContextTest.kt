package dev.renakoni.voicebox.bridge


import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GenerationContextTest {
    @Test
    fun cancelReachesRunningOperationWithoutWaitingForOwnerQueue() {
        val request = GenerationContext()
        val running = CountDownLatch(1)
        val terminate = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sawCancellation = AtomicBoolean()
        val owner = thread {
            try {
                request.run {
                    request.onCancel { terminate.countDown() }.use {
                        running.countDown()
                        assertTrue(terminate.await(2, TimeUnit.SECONDS))
                        GenerationContext.checkCurrent()
                    }
                }
            } catch (_: GenerationCancelledException) {
                sawCancellation.set(true)
            } finally { finished.countDown() }
        }
        assertTrue(running.await(2, TimeUnit.SECONDS))
        request.cancel("TIMEOUT")
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        owner.join()
        assertTrue(sawCancellation.get())
    }

    @Test
    fun unregisterFinishesBeforeOwnedRunOptionsCanBeClosed() {
        val request = GenerationContext()
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val closed = AtomicBoolean()
        val hook = request.onCancel {
            entered.countDown()
            assertTrue(leave.await(2, TimeUnit.SECONDS))
            assertFalse(closed.get())
        }
        val cancelThread = thread { request.cancel("CANCELLED") }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closeThread = thread { hook.close(); closed.set(true) }
        assertFalse(closed.get())
        leave.countDown()
        cancelThread.join(2000)
        closeThread.join(2000)
        assertTrue(closed.get())
    }

    @Test
    fun cancelledRequestCannotRegisterANewRunAndContextDoesNotLeak() {
        val request = GenerationContext()
        request.cancel("CANCELLED")
        assertThrows(GenerationCancelledException::class.java) { request.onCancel { fail() } }
        val active = GenerationContext()
        assertThrows(IllegalStateException::class.java) { active.run { error("failed") } }
        assertNull(GenerationContext.current())
    }
}
