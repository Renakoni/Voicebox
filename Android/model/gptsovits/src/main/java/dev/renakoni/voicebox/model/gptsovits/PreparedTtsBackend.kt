package dev.renakoni.voicebox.model.gptsovits

interface PreparedTtsBackend {
    fun generatePrepared(input: TtsPreparedInput, maxDecoderSteps: Int = 500,
        options: SynthesisOptions = SynthesisOptions.Default): TtsGenerationResult
}
