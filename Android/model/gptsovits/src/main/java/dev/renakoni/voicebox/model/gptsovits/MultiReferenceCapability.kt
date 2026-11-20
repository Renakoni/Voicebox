package dev.renakoni.voicebox.model.gptsovits

import ai.onnxruntime.OrtSession

data class MultiReferenceCapability(
    val supported: Boolean,
    val maxReferenceCount: Int = 0,
    val placeholderAudioSamples: Int = DEFAULT_PLACEHOLDER_AUDIO_SAMPLES,
) {
    fun validateReferenceCount(count: Int) {
        require(count > 0) { "At least one reference is required." }
        require(supported) { "The loaded model does not support auxiliary references." }
        require(count <= maxReferenceCount) {
            "At most $maxReferenceCount total references are supported."
        }
    }

    companion object {
        private const val INTERFACE_KEY = "genie.interface"
        private const val INTERFACE_VALUE = "multi_reference_v1"
        private const val MAX_COUNT_KEY = "genie.multi_reference.max_count"
        private const val PLACEHOLDER_AUDIO_SAMPLES_KEY =
            "genie.multi_reference.placeholder_audio_samples"
        private const val DEFAULT_PLACEHOLDER_AUDIO_SAMPLES = 2048

        fun fromSession(session: OrtSession): MultiReferenceCapability {
            val metadata = runCatching { session.metadata.customMetadata }.getOrDefault(emptyMap())
            return fromMetadata(
                metadata = metadata,
                inputNames = session.inputNames,
                outputNames = session.outputNames,
            )
        }

        internal fun fromMetadata(
            metadata: Map<String, String>,
            inputNames: Set<String>,
            outputNames: Set<String>,
        ): MultiReferenceCapability {
            val maxCount = metadata[MAX_COUNT_KEY]?.toIntOrNull() ?: 0
            val placeholderAudioSamples = metadata[PLACEHOLDER_AUDIO_SAMPLES_KEY]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_PLACEHOLDER_AUDIO_SAMPLES
            val supported = metadata[INTERFACE_KEY] == INTERFACE_VALUE &&
                maxCount > 0 &&
                inputNames.contains(REFERENCE_COUNT_INPUT) &&
                (0 until maxCount).all { "ref_audio_$it" in inputNames && "sv_emb_$it" in inputNames } &&
                REQUIRED_OUTPUTS.all(outputNames::contains)
            return MultiReferenceCapability(
                supported = supported,
                maxReferenceCount = if (supported) maxCount else 0,
                placeholderAudioSamples = placeholderAudioSamples,
            )
        }

        private val REQUIRED_OUTPUTS = setOf("ge", "ge_advanced")
        private const val REFERENCE_COUNT_INPUT = "reference_count"
    }
}
