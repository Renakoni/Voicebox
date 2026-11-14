package dev.rinstel.genie_tts.inference

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small frontend resources are bundled in the APK; inference never downloads them. */
internal object LanguageAssets {
    @Synchronized
    fun directory(context: Context, name: String): File {
        val root = File(context.filesDir, "language_frontends/$name").apply { mkdirs() }
        val assetRoot = "language_frontends/$name"
        val manifest = context.assets.open("$assetRoot/manifest.json").bufferedReader().use { it.readText() }
        val marker = File(root, "manifest.json")
        if (marker.isFile && marker.readText() == manifest) return root
        context.assets.list(assetRoot).orEmpty().filter { it != "manifest.json" }.forEach { name ->
            val temporary = File(root, "$name.tmp")
            try {
                context.assets.open("$assetRoot/$name").use { input -> temporary.outputStream().use(input::copyTo) }
                Files.move(temporary.toPath(), File(root, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        marker.writeText(manifest)
        return root
    }
}
