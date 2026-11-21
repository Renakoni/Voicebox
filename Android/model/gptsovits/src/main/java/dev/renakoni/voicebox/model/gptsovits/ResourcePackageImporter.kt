package dev.renakoni.voicebox.model.gptsovits

import android.util.JsonReader
import dev.renakoni.voicebox.core.FilePin
import dev.renakoni.voicebox.core.PinnedModelFiles
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Imports the runtime ZIP; voices use the checkpoint importer. */
internal class ResourcePackageImporter(private val filesRoot: File, private val managedPaths: Set<String>,
    private val files: PinnedModelFiles) {
    fun importRuntime(input: InputStream, checkCancelled: () -> Unit = {}) {
        requireUi(filesRoot.isDirectory || filesRoot.mkdirs()) { UiText("resource_root_failed") }
        val stage = File(filesRoot, IMPORT_STAGE)
        requireUi(stage.mkdirs()) { UiText("resource_staging_failed") }
        try {
            val seen = HashSet<String>()
            val imported = mutableMapOf<String, FilePin>()
            val buffer = ByteArray(128 * 1024)
            var bytes = 0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.replace('\\', '/').trimEnd('/')
                    val parts = path.split('/')
                    requireUi(parts.first() == "RuntimeAssets" && parts.none { it.isBlank() || it == "." || it == ".." || ':' in it }) {
                        UiText("resource_invalid_path")
                    }
                    requireUi(seen.add(path) && seen.size <= 100_000) { UiText("resource_duplicate_files") }
                    val output = File(stage, path)
                    if (entry.isDirectory) {
                        requireUi(output.mkdirs() || output.isDirectory) { UiText("resource_directory_failed") }
                        continue
                    }
                    requireUi(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory) { UiText("resource_directory_failed") }
                    val name = path.removePrefix("RuntimeAssets/")
                    val digest = if (name in managedPaths) MessageDigest.getInstance("SHA-256") else null
                    val start = bytes
                    val sink = if (name == RuntimeAssetRepository.HUBERT_FP32) null else output.outputStream().buffered()
                    sink.use { destination ->
                        while (true) {
                            checkCancelled()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            requireUi(bytes <= 4L * 1024 * 1024 * 1024) { UiText("resource_extraction_limit") }
                            destination?.write(buffer, 0, count)
                            digest?.update(buffer, 0, count)
                        }
                    }
                    if (digest != null) imported[name] = FilePin(bytes - start, digest.digest().toHexString())
                }
            }
            val source = File(stage, "RuntimeAssets")
            val target = File(filesRoot, "RuntimeAssets")
            val hasVocoder = File(source, "v5-vocoder").exists()
            val required = RuntimeAssetRepository.requiredFiles +
                OfficialLanguageAssets.requiredFiles(File(source, "language_frontends/english/external").isDirectory) +
                if (hasVocoder) managedPaths.filter { it.startsWith("v5-vocoder/") } else emptyList()
            val missing = required.filter { (imported[it]?.bytes ?: 0L) == 0L }
            requireUi(missing.isEmpty()) { UiText("resource_missing_files", missing.joinToString(", ")) }
            val weightBytes = imported.getValue(RuntimeAssetRepository.HUBERT_FP16).bytes
            requireUi(weightBytes % 2 == 0L && hubertRangesFit(File(source, RuntimeAssetRepository.HUBERT_MANIFEST), weightBytes * 2)) {
                UiText("download_size_mismatch")
            }
            val pins = JSONObject()
            if (!hasVocoder) readPins(target).filterKeys { it.startsWith("v5-vocoder/") }.forEach { (name, pin) ->
                pins.put(name, pin.toJson())
            }
            imported.forEach { (name, pin) -> pins.put(name, pin.toJson()) }
            File(source, LOCAL_LOCK).writeText(pins.toString())
            checkCancelled()
            if (target.isDirectory) Files.move(target.toPath(), File(stage, ".previous").toPath(), ATOMIC_MOVE)
            try {
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
            } finally {
                recover(filesRoot)
            }
            imported.forEach { (name, pin) -> files.record(File(target, name), pin) }
        } finally {
            // Keep the last usable runtime only if a failed replacement could not be rolled back.
            if (!File(stage, ".previous").exists()) stage.deleteRecursively()
        }
    }

    companion object {
        private const val LOCAL_LOCK = ".imported-runtime.json"
        private const val IMPORT_STAGE = ".gpt-packages/archive-import"

        private fun FilePin.toJson() = JSONObject().put("bytes", bytes).put("sha256", sha256)

        // The manifest addresses the expanded FP32 buffer. Check its ranges without reopening the weights.
        private fun hubertRangesFit(manifest: File, expandedBytes: Long): Boolean {
            JsonReader(manifest.bufferedReader()).use { json ->
                var hasTensors = false
                json.beginObject()
                while (json.hasNext()) {
                    if (json.nextName() != "initializers") { json.skipValue(); continue }
                    json.beginArray()
                    while (json.hasNext()) {
                        var offset = -1L
                        var length = 0L
                        json.beginObject()
                        while (json.hasNext()) when (json.nextName()) {
                            "offset" -> offset = json.nextLong()
                            "length" -> length = json.nextLong()
                            else -> json.skipValue()
                        }
                        json.endObject()
                        if (offset !in 0 until expandedBytes || length !in 1..(expandedBytes - offset)) return false
                        hasTensors = true
                    }
                    json.endArray()
                }
                json.endObject()
                return hasTensors
            }
        }

        internal fun readPins(runtime: File): Map<String, FilePin> {
            val file = File(runtime, LOCAL_LOCK)
            return try {
                val entries = JSONObject(Files.readAllBytes(file.toPath()).decodeToString())
                entries.keys().asSequence().associateWith { name ->
                    entries.getJSONObject(name).let { FilePin(it.getLong("bytes"), it.getString("sha256")) }
                }
            } catch (_: java.nio.file.NoSuchFileException) {
                emptyMap()
            } catch (error: org.json.JSONException) {
                android.util.Log.w("GptResources", "Unreadable local resource inventory", error)
                emptyMap()
            }
        }

        internal fun recover(root: File) {
            val stage = File(root, IMPORT_STAGE)
            if (!stage.exists()) return
            val previous = File(stage, ".previous")
            val target = File(root, "RuntimeAssets")
            if (previous.isDirectory && !target.exists()) {
                Files.move(previous.toPath(), target.toPath(), ATOMIC_MOVE)
            } else if (previous.isDirectory) {
                // A common-only ZIP reuses the installed vocoder in place on the same volume.
                val vocoder = File(target, "v5-vocoder")
                val saved = File(previous, "v5-vocoder")
                if (!vocoder.exists() && saved.isDirectory) Files.move(saved.toPath(), vocoder.toPath(), ATOMIC_MOVE)
            }
            check(stage.deleteRecursively())
        }
    }
}
