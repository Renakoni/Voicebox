package dev.renakoni.voicebox.bridge

data class GenerationRequest(
    val characterModel: CharacterModel,
    val language: String,
    val promptLanguage: String = language,
    val synthesisText: String,
    val referenceAudioPath: String,
    val referenceText: String,
    val auxiliaryReferenceAudioPaths: List<String> = emptyList(),
    val context: GenerationContext = GenerationContext(),
    val options: Map<String, String> = emptyMap(),
)
