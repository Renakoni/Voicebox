package dev.renakoni.voicebox.inference

interface TtsInputPreparer {
    fun prepare(request: GenerationRequest): TtsPreparedInput
}
