package dev.renakoni.voicebox

import dev.renakoni.voicebox.inference.GenerationContext

/** Closes the enqueue-to-first-progress race for UI and HTTP synthesis requests. */
class GenerationRequestGate {
    private var active: GenerationContext? = null
    private var epoch = 0L
    private var stopped = false
    private var latestRequestId: String? = null
    private var delivered = false

    @Synchronized
    fun tryAcquire(context: GenerationContext = GenerationContext()): Boolean {
        if (stopped || active != null || context.cancellationReason != null) return false
        context.generationEpoch = epoch
        active = context
        latestRequestId = context.requestId
        delivered = false
        return true
    }

    @Synchronized
    fun release(context: GenerationContext? = active) {
        if (active === context) active = null
    }

    @Synchronized
    fun isClaimed(): Boolean = active != null

    @Synchronized
    fun cancel(requestId: String, reason: String): Boolean {
        if (delivered) return false
        val context = active?.takeIf { it.requestId == requestId } ?: return false
        epoch++
        context.cancel(reason)
        return true
    }

    @Synchronized
    fun invalidate(reason: String) {
        epoch++
        active?.cancel(reason)
    }

    @Synchronized
    fun stop() {
        stopped = true
        invalidate("SERVICE_DESTROYED")
    }

    @Synchronized
    fun isCurrent(context: GenerationContext): Boolean =
        !stopped && context.requestId == latestRequestId &&
            context.generationEpoch == epoch && context.cancellationReason == null

    @Synchronized
    fun deliver(context: GenerationContext, action: () -> Unit): Boolean {
        if (!isCurrent(context)) return false
        action()
        delivered = true
        return true
    }
}
