package dev.renakoni.voicebox.inference

data class PromptEmbeddings(
    val globalEmbedding: FloatTensorData,
    val advancedGlobalEmbedding: FloatTensorData,
)
