package dev.renakoni.voicebox.bridge

interface BackendRuntime : AutoCloseable {
    val backend: ExecutionBackend
    val runtimeLabel: String
        get() = backend.label

    fun generate(
        request: GenerationRequest,
        callbacks: BackendRuntimeCallbacks,
    ): GeneratedAudioFile

    /** Prepare the active reference and synthesis sessions while the UI is idle. */
    fun warmup(request: GenerationRequest) = Unit

    fun trimMemory() = Unit
}
