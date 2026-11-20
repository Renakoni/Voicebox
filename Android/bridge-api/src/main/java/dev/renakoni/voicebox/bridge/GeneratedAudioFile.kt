package dev.renakoni.voicebox.bridge

import java.io.File

data class GeneratedAudioFile(
    val file: File,
    val audioSamples: Int,
    val sampleRate: Int,
)
