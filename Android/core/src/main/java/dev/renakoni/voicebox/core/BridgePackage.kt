package dev.renakoni.voicebox.core

import android.content.Context
import dev.renakoni.voicebox.bridge.BackendEntry
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.ZipFile

/** Only catalogs shipped with the application can name executable packages. */
class BridgePackageSpec(json: JSONObject) {
    val id = json.getString("id")
    val displayName = json.optString("displayName", id)
    val revision = json.getString("revision")
    val abi = json.getString("abi")
    val entry = json.getString("entry")
    val code = json.getString("code")
    val sourceCommit = json.getString("sourceCommit")
    val adapterSource = json.getString("adapterSourceSha256")
    val contract = json.getInt("contract")
    val parts = json.getJSONArray("parts").let { items -> List(items.length()) { i ->
        val part = items.getJSONObject(i)
        ModelDownload(part.getString("url"), part.getLong("bytes"), part.getString("sha256"))
    } }
    val files = json.getJSONObject("files").let { entries ->
        entries.keys().asSequence().associateWith { entries.getLong(it) }
    }
    val bundled get() = parts.all { it.url.startsWith("asset://") }
    val downloadBytes get() = parts.sumOf { it.bytes }

    init {
        require(json.getInt("format") == 2 && contract == BackendEntry.CONTRACT)
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}")) && revision.matches(HASH))
        require(adapterSource.matches(HASH) && sourceCommit.matches(Regex("[a-f0-9]{40}")) && abi in setOf("arm64-v8a", "x86_64"))
        require(entry.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(parts.size in 1..64 && parts.all {
            (it.url.startsWith("https://") || it.url.startsWith("asset://") && safePath(it.url.removePrefix("asset://"))) &&
                it.bytes in 1..2_000_000_000L && it.sha256.matches(HASH)
        })
        require(files.size in 2..4096 && files.all { (name, size) ->
            safePath(name) && size in 0..1_900_000_000L
        })
        require(code in files && (code.endsWith(".jar") || code.endsWith(".apk")) && "bridge.json" in files)
    }

    fun verifyManifest(manifest: JSONObject) {
        require(manifest.getString("id") == id && manifest.getInt("contract") == contract &&
            manifest.getString("entry") == entry && manifest.getString("abi") == abi &&
            manifest.getString("code") == code && manifest.getString("sourceCommit") == sourceCommit &&
            manifest.getString("adapterSourceSha256") == adapterSource) {
            "Bridge manifest differs from the application catalog"
        }
    }

    companion object {
        private val HASH = Regex("[a-f0-9]{64}")
        internal fun safePath(name: String) = name.length in 1..512 && name.split('/').all {
            it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9.+_-]*"))
        }
    }
}

class BridgePackages(private val context: Context) {
    private val processAbi = DeviceFacts.read(context).abi
    val packages = context.assets.list("bridges").orEmpty().filter { it.endsWith(".json") }.map { name ->
        val spec = context.assets.open("bridges/$name").bufferedReader().use { BridgePackageSpec(JSONObject(it.readText())) }
        require(name == "${spec.id}.json")
        BridgePackage(File(context.noBackupFilesDir, "model-runtimes/${spec.id}"), spec, processAbi) {
            part, destination, cancel, progress ->
            if (part.url.startsWith("asset://")) {
                context.assets.open(part.url.removePrefix("asset://")).use {
                    copyVerifiedDownload(part, it, destination, cancel, progress)
                }
            } else downloadModelFile(part, destination, cancel, progress = progress)
        }
    }
    fun installBundled() {
        packages.filter { it.spec.bundled }.forEach { it.install({}, {}) }
    }
    fun get(id: String) = packages.single { it.spec.id == id }
}

class BridgePackage(
    private val root: File, val spec: BridgePackageSpec, private val abi: String,
    private val download: (ModelDownload, File, () -> Unit, (Int) -> Unit) -> Unit,
) {
    val supported get() = spec.abi == abi
    private val revisions get() = File(root, spec.revision)
    val directory: File? get() = if (!supported) null else revisions.listFiles()?.firstOrNull { candidate ->
        File(candidate, ".complete").let { it.isFile && it.readText() == spec.revision } &&
            spec.files.all { (name, bytes) -> File(candidate, name).let { it.isFile && it.length() == bytes } }
    }
    val installed get() = directory != null

    fun install(checkCancelled: () -> Unit, progress: (Int) -> Unit) {
        require(supported) { "Unsupported backend ABI: ${spec.abi}" }
        checkCancelled()
        synchronized(installLock) {
            root.mkdirs()
            FileChannel.open(File(root, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    if (installed) return
                    root.listFiles()?.filter { it.name.startsWith(".stage-") || it.name == ".download" }
                        ?.forEach { it.deleteRecursively() }
                    require(root.usableSpace > spec.files.values.sum() + spec.parts.maxOf { it.bytes })
                    val id = UUID.randomUUID().toString()
                    val staging = File(root, ".stage-$id").also { check(it.mkdir()) }
                    val archive = File(root, ".download")
                    val seen = mutableSetOf<String>()
                    var received = 0L
                    try {
                        spec.parts.forEach { part ->
                            download(part, archive, checkCancelled) { percent ->
                                progress(((received + part.bytes * percent / 100) * 99 / spec.downloadBytes).toInt())
                            }
                            extract(archive, staging, seen, checkCancelled)
                            received += part.bytes
                            archive.delete()
                        }
                        require(seen == spec.files.keys) { "Incomplete bridge package" }
                        require(File(staging, "bridge.json").length() <= 16_384)
                        spec.verifyManifest(JSONObject(File(staging, "bridge.json").readText()))
                        check(File(staging, spec.code).setReadOnly()) { "Cannot protect bridge DEX" }
                        File(staging, ".complete").writeText(spec.revision)
                        revisions.mkdirs()
                        checkCancelled()
                        Files.move(staging.toPath(), File(revisions, id).toPath(), StandardCopyOption.ATOMIC_MOVE)
                        progress(100)
                    } finally {
                        archive.delete()
                        staging.deleteRecursively()
                    }
                }
            }
        }
    }

    internal fun extract(archive: File, staging: File, seen: MutableSet<String>, checkCancelled: () -> Unit) {
        ZipFile(archive).use { zip ->
            val buffer = ByteArray(128 * 1024)
            for (entry in zip.entries()) {
                checkCancelled()
                require(!entry.isDirectory && BridgePackageSpec.safePath(entry.name) && seen.add(entry.name)) {
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

    private companion object { val installLock = Any() }
}
