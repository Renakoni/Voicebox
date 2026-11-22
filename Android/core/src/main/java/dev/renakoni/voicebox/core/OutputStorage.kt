package dev.renakoni.voicebox.core

import java.io.File

class OutputStorage(private val root: File) {
    fun outputRoot(): File = File(root, "GeneratedAudio").also { it.mkdirs() }
}
