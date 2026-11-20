package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.GenerationRequest

interface TtsInputPreparer {
    fun prepare(request: GenerationRequest): TtsPreparedInput
}
