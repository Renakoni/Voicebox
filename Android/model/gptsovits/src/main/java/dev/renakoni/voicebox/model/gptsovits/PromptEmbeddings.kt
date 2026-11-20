package dev.renakoni.voicebox.model.gptsovits

data class PromptEmbeddings(
    val globalEmbedding: FloatTensorData,
    val advancedGlobalEmbedding: FloatTensorData,
)
