package dev.renakoni.voicebox.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import dev.renakoni.voicebox.bridge.GenerationContext

class GenerationRequestGateTest {
    @Test
    fun timeoutDoesNotReleaseResourcesBeforeOwnerReturnsOrCancelNextRequest() {
        val gate = GenerationRequestGate()
        val old = GenerationContext("old")
        val next = GenerationContext("next")
        assertTrue(gate.tryAcquire(old))
        assertTrue(gate.cancel("old", "TIMEOUT"))
        assertFalse(gate.deliver(old) { error("Stale audio escaped") })
        assertFalse(gate.tryAcquire(next))
        gate.release(old)
        assertTrue(gate.tryAcquire(next))
        assertFalse(gate.cancel("old", "TIMEOUT"))
        gate.release(old)
        assertTrue(gate.isClaimed())
        assertTrue(gate.deliver(next) {})
        assertEquals(1L, next.generationEpoch)
    }

    @Test
    fun switchInvalidatesQueuedResultAndDestructionRejectsNewWork() {
        val gate = GenerationRequestGate()
        val request = GenerationContext()
        gate.tryAcquire(request)
        gate.invalidate("VOICE_CHANGED")
        assertFalse(gate.isCurrent(request))
        assertEquals("VOICE_CHANGED", request.cancellationReason)
        gate.release(request)
        gate.stop()
        assertFalse(gate.tryAcquire(GenerationContext()))
    }

    @Test
    fun completionWinningDeadlineRaceCannotThenBeTimedOut() {
        val gate = GenerationRequestGate()
        val request = GenerationContext()
        gate.tryAcquire(request)
        assertTrue(gate.deliver(request) {})
        assertFalse(gate.cancel(request.requestId, "TIMEOUT"))
    }

    @Test
    fun delayedUiDeliveryFromPreviousRequestIsDiscarded() {
        val gate = GenerationRequestGate()
        val first = GenerationContext()
        gate.tryAcquire(first)
        gate.release(first)
        gate.tryAcquire(GenerationContext())
        assertFalse(gate.isCurrent(first))
    }


}
