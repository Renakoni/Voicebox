package dev.rinstel.genie_tts.moremodels

import android.content.Context
import android.os.Build
import dev.rinstel.genie_tts.ModelDownload
import dev.rinstel.genie_tts.downloadModelFile
import dev.rinstel.genie_tts.inference.CharacterModel
import dev.rinstel.genie_tts.inference.ReferenceExample
import dev.rinstel.genie_tts.inference.RequiredModelFiles
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

object MingModel {
    const val ID = "ming-dense-0.5b"
    const val DIRECTORY = "ming/dense-0.5b/models"
    val model = CharacterModel(ID, "Ming-TTS 0.5B", RequiredModelFiles(
        variantName = "Ming-TTS 0.5B", assetSubdirectory = DIRECTORY,
        files = emptyList(), sessionModels = emptyList(),
    ))
    fun matches(model: CharacterModel) = model.id == ID && model.relativeModelDirectory == DIRECTORY
}

/** Pinned release metadata. Only these archives may supply executable code. */
class MingPackageSpec(json: JSONObject) {
    val revision = json.getString("revision")
    val abi = json.getString("abi")
    val parts = json.getJSONArray("parts").let { array -> List(array.length()) { index ->
        val item = array.getJSONObject(index)
        ModelDownload(item.getString("url"), item.getLong("bytes"), item.getString("sha256"))
    } }
    val files = json.getJSONObject("files").let { entries ->
        entries.keys().asSequence().associateWith { entries.getLong(it) }
    }
    val downloadBytes get() = parts.sumOf { it.bytes }
    val unpackedBytes get() = files.values.sum()

    init {
        require(json.getInt("format") == 1 && json.getString("id") == MingModel.ID)
        require(revision.matches(Regex("[a-f0-9]{64}")))
        require(abi in listOf("arm64-v8a", "x86_64"))
        require(parts.isNotEmpty() && parts.size <= 64 && parts.all {
            it.url.startsWith("https://") && it.bytes in 1..2_000_000_000L && it.sha256.matches(Regex("[a-f0-9]{64}"))
        })
        require(files.isNotEmpty() && files.size <= 1024 && files.all { (name, size) -> safePath(name) && size in 0..1_900_000_000L })
        require(listOf("tasks.jar", "libonnxruntime.so", "libming-synthesize.so", "libming-reference.so").all(files::containsKey))
        require(files.keys.all { it in setOf("tasks.jar", "libonnxruntime.so", "libming-synthesize.so",
            "libming-reference.so", "libming-mmla-neon.so", "libming-mmla-bf16.so") })
    }

    companion object {
        internal fun safePath(name: String) = name.isNotEmpty() && !name.startsWith('/') &&
            '\\' !in name && ':' !in name && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}

class MingPackageRepository(private val root: File, val spec: MingPackageSpec,
    private val download: (ModelDownload, File, () -> Unit, (Int) -> Unit) -> Unit = { source, target, cancel, progress ->
        downloadModelFile(source, target, cancel, progress = progress)
    },
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "model-runtimes/ming"),
        MingPackageSpec(context.assets.open("ming-runtime.json").bufferedReader().use { JSONObject(it.readText()) }))

    val directory get() = File(root, spec.revision)
    val installed get() = runCatching {
        File(directory, ".complete").let { marker ->
            marker.isFile && marker.readText() == spec.revision &&
                spec.files.all { (name, bytes) -> File(directory, name).let { it.isFile && it.length() == bytes } }
        }
    }.getOrDefault(false)
    val supported get() = spec.abi == Build.SUPPORTED_ABIS.firstOrNull()

    fun install(checkCancelled: () -> Unit, progress: (Int) -> Unit) {
        if (installed) return
        root.mkdirs()
        // Recover staging space after a process exit during a previous download.
        root.listFiles()?.filter { it.name.startsWith(".stage-") || it.name.startsWith(".download-") }
            ?.forEach { it.deleteRecursively() }
        require(root.usableSpace > spec.unpackedBytes + spec.parts.maxOf { it.bytes }) { "Not enough space for Ming-TTS" }
        val staging = File(root, ".stage-${UUID.randomUUID()}").apply { mkdirs() }
        val seen = mutableSetOf<String>()
        var downloaded = 0L
        try {
            spec.parts.forEach { part ->
                val archive = File(root, ".download-${UUID.randomUUID()}.zip")
                try {
                    download(part, archive, checkCancelled) { percent ->
                        progress(((downloaded + part.bytes * percent / 100) * 100 / spec.downloadBytes).toInt().coerceAtMost(99))
                    }
                    extract(archive, staging, seen, checkCancelled)
                    downloaded += part.bytes
                } finally { archive.delete() }
            }
            require(seen == spec.files.keys) { "Incomplete Ming runtime package" }
            checkCancelled()
            // Android 14+ requires dynamically loaded DEX to be read-only before loading.
            require(File(staging, "tasks.jar").setReadOnly()) { "Cannot protect Ming task code" }
            File(staging, ".complete").writeText(spec.revision)
            // The inference service serializes installation and synthesis. Never replace a live runtime.
            if (directory.exists()) directory.deleteRecursively()
            Files.move(staging.toPath(), directory.toPath())
            progress(100)
        } finally { staging.deleteRecursively() }
    }

    internal fun extract(archive: File, staging: File, seen: MutableSet<String>, checkCancelled: () -> Unit) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(128 * 1024)
            while (entries.hasMoreElements()) {
                checkCancelled()
                val entry = entries.nextElement()
                require(!entry.isDirectory && MingPackageSpec.safePath(entry.name) && seen.add(entry.name)) {
                    "Invalid or duplicate runtime path"
                }
                val expected = requireNotNull(spec.files[entry.name]) { "Unexpected runtime file" }
                require(entry.size == expected) { "Runtime file size mismatch" }
                val target = File(staging, entry.name)
                target.parentFile!!.mkdirs()
                var copied = 0L
                zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= expected) { "Runtime file exceeds expected size" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                require(copied == expected) { "Truncated runtime file" }
            }
        }
    }
}

/** Model data belongs to the voice/model flow, independently of the downloaded backend. */
class MingModelResources(context: Context) {
    val directory = File(context.getExternalFilesDir(null), "CharacterModels/ming/dense-0.5b")
    val available get() = requiredFiles.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }

    fun reference(): ReferenceExample? {
        if (listOf("voice/manifest.json", "voice/speaker.f32", "voice/prompt.f32", "voice/history.f32", "examples/reference.wav")
                .any { !File(directory, it).isFile }) return null
        val voice = JSONObject(File(directory, "voice/manifest.json").readText())
        return ReferenceExample(File(directory, "examples/reference.wav"), voice.getString("prompt_text"), "zh")
    }

    companion object {
        private val requiredFiles = listOf(
            "models/tokenizer.json", "models/llm.onnx", "models/llm.data", "models/embedding.f32",
            "models/flow.onnx", "models/flow.data", "models/aggregator.onnx", "models/aggregator.data",
            "models/audio.onnx", "models/audio.data", "models/time.onnx", "models/time.data", "models/window.f32",
            "reference/encoder.onnx", "reference/encoder.data", "reference/speaker.onnx", "reference/campplus.onnx",
            "reference/projection.onnx", "reference/projection.data", "reference/mel.f32", "reference/povey.f32",
        )
    }
}
