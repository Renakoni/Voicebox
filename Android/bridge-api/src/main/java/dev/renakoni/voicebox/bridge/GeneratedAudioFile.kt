package dev.renakoni.voicebox.bridge

import java.io.File

data class GeneratedAudioFile @JvmOverloads constructor(
    val file: File,
    val audioSamples: Int,
    val sampleRate: Int,
    val inferenceSeconds: Double? = null,
)
