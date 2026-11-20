package dev.renakoni.voicebox.inference

data class TtsGenerationResult(
    val audio: FloatArray,
    val shape: LongArray,
    val sampleRate: Int = 32000,
) {
    override fun equals(other: Any?): Boolean =
        other is TtsGenerationResult && audio.contentEquals(other.audio) && shape.contentEquals(other.shape) && sampleRate == other.sampleRate

    override fun hashCode(): Int = 31 * (31 * audio.contentHashCode() + shape.contentHashCode()) + sampleRate
}
