package dev.rinstel.genie_tts

import dev.rinstel.genie_tts.checkpoint.CharacterVoiceEditor
import dev.rinstel.genie_tts.inference.RuntimeAssetRepository
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream

/** Imports the runtime ZIP; voices use the checkpoint importer. */
class ResourcePackageImporter(private val filesRoot: File) {
    fun importRuntime(input: InputStream, checkCancelled: () -> Unit = {}) {
        require(filesRoot.isDirectory || filesRoot.mkdirs()) { "无法创建应用资源目录。" }
        val stage = File(filesRoot, ".genie-import-${UUID.randomUUID()}")
        require(stage.mkdir()) { "无法创建资源导入临时目录。" }
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
                    require(parts.first() == "RuntimeAssets" && parts.none { it.isBlank() || it == "." || it == ".." || ':' in it }) {
                        "资源包必须位于 RuntimeAssets/ 内，且不能包含非法路径。"
                    }
                    require(seen.add(path) && seen.size <= 100_000) { "资源包文件重复或数量过多。" }
                    val output = File(stage, path)
                    if (entry.isDirectory) {
                        require(output.mkdirs() || output.isDirectory) { "无法创建资源目录。" }
                        continue
                    }
                    require(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory) { "无法创建资源目录。" }
                    output.outputStream().buffered().use { destination ->
                        while (true) {
                            checkCancelled()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= 4L * 1024 * 1024 * 1024) { "资源包解压内容超过 4 GiB 限制。" }
                            destination.write(buffer, 0, count)
                        }
                    }
                }
            }
            val missing = RuntimeAssetRepository(stage).inspect().missingFiles
            require(missing.isEmpty()) { "资源包缺少文件：${missing.joinToString("、")}" }
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
