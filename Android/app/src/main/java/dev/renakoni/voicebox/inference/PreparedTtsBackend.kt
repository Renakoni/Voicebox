package dev.renakoni.voicebox.inference

interface PreparedTtsBackend {
    fun generatePrepared(input: TtsPreparedInput, maxDecoderSteps: Int = 500,
        options: SynthesisOptions = SynthesisOptions.Default): TtsGenerationResult
}
