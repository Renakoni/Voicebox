package dev.renakoni.voicebox

import dev.renakoni.voicebox.i18n.UiText
import dev.renakoni.voicebox.i18n.requireUi

import dev.renakoni.voicebox.checkpoint.CharacterVoiceEditor
import dev.renakoni.voicebox.inference.RuntimeAssetRepository
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream

/** Imports the runtime ZIP; voices use the checkpoint importer. */
class ResourcePackageImporter(private val filesRoot: File) {
    fun importRuntime(input: InputStream, checkCancelled: () -> Unit = {}) {
        requireUi(filesRoot.isDirectory || filesRoot.mkdirs()) { UiText(R.string.resource_root_failed) }
        val stage = File(filesRoot, ".voicebox-import-${UUID.randomUUID()}")
        requireUi(stage.mkdir()) { UiText(R.string.resource_staging_failed) }
        try {
            val seen = HashSet<String>()
            val buffer = ByteArray(128 * 1024)
            var bytes = 0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.replace('\\', '/').trimEnd('/')
                    val parts = path.split('/')
                    requireUi(parts.first() == "RuntimeAssets" && parts.none { it.isBlank() || it == "." || it == ".." || ':' in it }) {
                        UiText(R.string.resource_invalid_path)
                    }
                    requireUi(seen.add(path) && seen.size <= 100_000) { UiText(R.string.resource_duplicate_files) }
                    val output = File(stage, path)
                    if (entry.isDirectory) {
                        requireUi(output.mkdirs() || output.isDirectory) { UiText(R.string.resource_directory_failed) }
                        continue
                    }
                    requireUi(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory) { UiText(R.string.resource_directory_failed) }
                    output.outputStream().buffered().use { destination ->
                        while (true) {
                            checkCancelled()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            requireUi(bytes <= 4L * 1024 * 1024 * 1024) { UiText(R.string.resource_extraction_limit) }
                            destination.write(buffer, 0, count)
                        }
                    }
                }
            }
            val missing = RuntimeAssetRepository(stage).inspect().missingFiles
            requireUi(missing.isEmpty()) { UiText(R.string.resource_missing_files, missing.joinToString(", ")) }
            checkCancelled()
            val source = File(stage, "RuntimeAssets")
            val target = File(filesRoot, "RuntimeAssets")
            if (target.isDirectory) CharacterVoiceEditor.replaceDirectory(source, target)
            else Files.move(source.toPath(), target.toPath())
        } finally {
            // Keep the last usable runtime only if a failed replacement could not be rolled back.
            if (!File(stage, ".previous").exists()) stage.deleteRecursively()
        }
    }
}
