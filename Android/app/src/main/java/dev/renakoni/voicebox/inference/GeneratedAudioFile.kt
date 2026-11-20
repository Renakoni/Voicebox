package dev.renakoni.voicebox.inference

import java.io.File

data class GeneratedAudioFile(
    val file: File,
    val audioSamples: Int,
    val tensorShape: LongArray,
    val sampleRate: Int = 32000,
) {
    override fun equals(other: Any?): Boolean =
        other is GeneratedAudioFile &&
            file == other.file &&
            audioSamples == other.audioSamples &&
            tensorShape.contentEquals(other.tensorShape) && sampleRate == other.sampleRate

    override fun hashCode(): Int = 31 * (31 * (31 * file.hashCode() + audioSamples) + tensorShape.contentHashCode()) + sampleRate
}
