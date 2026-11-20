package dev.renakoni.voicebox.inference

import java.io.File

data class ReferenceExample(
    val audioFile: File,
    val referenceText: String,
    val language: String = "auto",
)
