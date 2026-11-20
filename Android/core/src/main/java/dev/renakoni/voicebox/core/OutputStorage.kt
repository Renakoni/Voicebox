package dev.renakoni.voicebox.core

import java.io.File

class OutputStorage(private val root: File) {
    fun outputRoot(): File = File(root, "GeneratedAudio").also { it.mkdirs() }
    fun clearGeneratedAudio() = outputRoot().listFiles().orEmpty().count {
        it.isFile && it.extension.equals("wav", ignoreCase = true) && it.delete()
    }
}
