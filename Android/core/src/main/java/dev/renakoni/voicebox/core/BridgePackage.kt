package dev.renakoni.voicebox.core

import android.content.Context
import dev.renakoni.voicebox.bridge.ResourcePack
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.ZipFile

class BridgePackageSpec(internal val json: JSONObject) {
    val id = json.getString("id")
    val displayName = json.optString("displayName", id)
    val minAppVersionCode = json.optLong("minAppVersionCode", 1)
    val minSdk = json.optInt("minSdk")
    val requiresVulkan12Storage16 = json.optBoolean("requiresVulkan12Storage16")
    val version = json.optInt("version", 1)
    val revision = json.optString("revision", "v$version")
    val abi = json.getString("abi")
    val entry = json.getString("entry")
    val code = json.getString("code")
    val contract = json.getInt("contract")
    internal val parts = json.getJSONArray("parts").let { items -> List(items.length()) { i ->
        val part = items.getJSONObject(i)
        BridgePart(part.getString("url"), part.getLong("bytes"))
    } }
    val files = json.getJSONObject("files").let { entries ->
        entries.keys().asSequence().associateWith { entries.getLong(it) }
    }
    val initialResourcePacks = json.optJSONArray("initialResourcePacks")?.let { entries ->
        List(entries.length()) { index ->
            val pack = entries.getJSONObject(index)
            val bytes = pack.getLong("bytes")
            ResourcePack(pack.getString("id"), pack.getString("titleKey"), bytes, bytes, false, emptySet())
        }
    }.orEmpty()
    val downloadBytes get() = parts.sumOf { it.bytes }
    internal fun matchesHost(base: BridgePackageSpec, hostAbi: String, sdk: Int, appVersion: Long) =
        id == base.id && contract == base.contract && abi == hostAbi && minSdk <= sdk && minAppVersionCode <= appVersion

    init {
        require(json.getInt("format") == 2 && contract > 0 && version > 0)
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}")) && safeSegment(revision))
        require(abi in setOf("arm64-v8a", "x86_64"))
        require(entry.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(parts.size in 1..64 && parts.all {
            it.url.startsWith("https://") &&
                it.bytes in 1..2_000_000_000L
        })
        require(files.size in 2..4096 && files.all { (name, size) ->
            safePath(name) && size in 0..1_900_000_000L
        })
        require(code in files && (code.endsWith(".jar") || code.endsWith(".apk")) && "bridge.json" in files)
    }

    fun verifyManifest(manifest: JSONObject) {
        require(manifest.getString("id") == id && manifest.getInt("contract") == contract &&
            manifest.getString("entry") == entry && manifest.getString("abi") == abi &&
            manifest.getString("code") == code) {
            "Bridge manifest differs from the application catalog"
        }
    }

    companion object {
        internal fun safeSegment(name: String) = '/' !in name && safePath(name)
        internal fun safePath(name: String) = name.length in 1..512 && name.split('/').all {
            it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9.+_-]*"))
        }
    }
}

class BridgePackages(private val context: Context) {
    private val processAbi = DeviceFacts.read(context).abi
    private val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    val packages = context.assets.list("bridges").orEmpty().filter { it.endsWith(".json") }.map { name ->
        val spec = context.assets.open("bridges/$name").bufferedReader().use { BridgePackageSpec(JSONObject(it.readText())) }
        require(name == "${spec.id}.json")
        BridgePackage(File(context.noBackupFilesDir, "model-runtimes/${spec.id}"), spec, processAbi, appVersion,
            modelImportDirectory(context))
    }
    fun get(id: String) = packages.single { it.spec.id == id }
    private val releases by lazy { BridgeReleases(packages) }
    fun refreshOffers() = releases.refresh()
}

data class InstalledBridge(val id: String, val entry: String, val code: String, val directory: File) {
    val revision: String get() = directory.parentFile!!.name
}

enum class BridgeInstallState { MISSING, UPDATE_REQUIRED, UPDATE_AVAILABLE, CURRENT, INCOMPATIBLE;
    val usable get() = this == CURRENT || this == UPDATE_AVAILABLE
}

class BridgePackage(
    private val root: File, internal val bundled: BridgePackageSpec, private val abi: String, private val appVersion: Long,
    private val imports: File? = null,
) {
    @Volatile var spec = bundled
        internal set
    val compatiblePlatform get() = spec.abi == abi && android.os.Build.VERSION.SDK_INT >= spec.minSdk
    val supported get() = compatiblePlatform && supportsGpu(spec)
    val installable get() = supported && spec.minAppVersionCode <= appVersion

    private fun supportsGpu(candidate: BridgePackageSpec) =
        if (candidate.requiresVulkan12Storage16) DeviceFacts.hasVulkan12Storage16 else DeviceFacts.hasVulkanCompute

    internal fun accepts(candidate: BridgePackageSpec) =
        candidate.matchesHost(bundled, abi, android.os.Build.VERSION.SDK_INT, appVersion) && supportsGpu(candidate)
    private val lockFile = File(root, ".lock")
    private val receipt = File(root, "active.json")
    private data class Slot(val directory: File, val version: Int, val minSdk: Int) {
        val revision get() = directory.parentFile!!.name
    }
    val active get() = installedSlot()?.asActive()

    private fun Slot.asActive(): InstalledBridge? {
        val target = spec
        val known = when (revision) {
            target.revision -> target
            bundled.revision -> bundled
            else -> null
        }
        return runCatching {
            val meta = known?.json ?: JSONObject(File(directory, "bridge.json").readText())
            val code = meta.getString("code")
            if (meta.getString("id") != bundled.id || meta.getInt("contract") != bundled.contract ||
                meta.getString("abi") != abi || meta.optLong("minAppVersionCode", 1) > appVersion ||
                android.os.Build.VERSION.SDK_INT < (known?.minSdk ?: minSdk) ||
                !BridgePackageSpec.safePath(code) || !File(directory, code).isFile) return null
            InstalledBridge(bundled.id, meta.getString("entry"), code, directory)
        }.getOrNull()
    }
    private fun Slot.upToDate(target: BridgePackageSpec) = revision == target.revision || (version > target.version && asActive() != null)

    val installState get() = installedSlot()?.let {
        when {
            !supported -> BridgeInstallState.INCOMPATIBLE
            it.upToDate(spec) -> BridgeInstallState.CURRENT
            it.asActive() != null -> BridgeInstallState.UPDATE_AVAILABLE
            else -> BridgeInstallState.UPDATE_REQUIRED
        }
    } ?: BridgeInstallState.MISSING
    val installed get() = installState.usable

    private fun installedSlot(): Slot? {
        if (!receipt.isFile) return null
        return runCatching {
            val json = JSONObject(receipt.readText())
            val revision = json.getString("revision")
            require(BridgePackageSpec.safeSegment(revision))
            val slot = json.getString("directory")
            require(BridgePackageSpec.safeSegment(slot))
            val directory = File(File(root, revision), slot)
            // Every activated slot has a manifest, even if a new revision renames its entry archive.
            val file = if (revision == spec.revision) spec.code else "bridge.json"
            if (File(directory, file).isFile) Slot(directory, json.optInt("version"), json.optInt("minSdk", bundled.minSdk)) else null
        }.getOrNull()
    }

    fun install(checkCancelled: () -> Unit, progress: (Int) -> Unit): Boolean {
        checkCancelled()
        return withInstallLock {
            checkCancelled()
            val spec = this.spec
            require(accepts(spec)) { "Bridge package is incompatible with this app" }
            val previous = installedSlot()
            if (previous?.upToDate(spec) == true) return@withInstallLock false
            clearInstallations(previous?.directory)
            val revisions = File(root, spec.revision)
            require(root.usableSpace > spec.files.values.sum() + spec.parts.maxOf { it.bytes })
            val id = UUID.randomUUID().toString()
            val staging = File(root, ".stage-$id").also { check(it.mkdir()) }
            val archive = File(root, ".download")
            try {
                val seen = mutableSetOf<String>()
                var received = 0L
                spec.parts.forEach { part ->
                    stageBridgePart(part, archive, stagedImport(spec, part), checkCancelled) { percent ->
                        progress(((received + part.bytes * percent / 100) * 99 / spec.downloadBytes).toInt())
                    }
                    extract(spec, archive, staging, seen, checkCancelled)
                    received += part.bytes
                    Files.delete(archive.toPath())
                }
                require(seen == spec.files.keys) { "Incomplete bridge package" }
                require(File(staging, "bridge.json").length() <= 16_384)
                spec.verifyManifest(JSONObject(File(staging, "bridge.json").readText()))
                check(File(staging, spec.code).setReadOnly()) { "Cannot protect bridge DEX" }
                revisions.mkdirs()
                checkCancelled()
                val active = File(revisions, id)
                Files.move(staging.toPath(), active.toPath(), StandardCopyOption.ATOMIC_MOVE)
                activate(active, spec)
                clearInstallations(active)
            } finally {
                Files.deleteIfExists(archive.toPath())
                if (staging.exists()) deleteFileTree(staging)
            }
            progress(100)
            true
        }
    }

    private fun stagedImport(spec: BridgePackageSpec, part: BridgePart): File? {
        val root = imports ?: return null
        val name = part.url.substringAfterLast('/')
        val versioned = File(root, "${spec.id}-runtime-v${spec.version}/$name")
        if (versioned.isFile) return versioned
        // Some releases reuse the same ZIP filename across versions.
        return File(root, name).takeIf { spec.version == bundled.version || "-v${spec.version}-" in name }
    }

    fun uninstall() {
        withInstallLock {
            Files.deleteIfExists(receipt.toPath())
            clearInstallations()
        }
    }

    private fun clearInstallations(keep: File? = null) {
        requireNotNull(root.listFiles()).forEach { file ->
            when {
                file == lockFile || (keep != null && file == receipt) -> Unit
                file == keep?.parentFile -> file.listFiles().orEmpty().filter { it != keep }.forEach(::deleteFileTree)
                else -> deleteFileTree(file)
            }
        }
    }

    private inline fun <T> withInstallLock(action: () -> T): T = synchronized(installLock) {
        root.mkdirs()
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun activate(directory: File, spec: BridgePackageSpec) {
        val pending = File(root, ".active")
        try {
            pending.writeText(JSONObject().put("revision", spec.revision).put("directory", directory.name)
                .put("version", spec.version).put("minSdk", spec.minSdk).toString())
            Files.move(pending.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }

    private fun extract(spec: BridgePackageSpec, archive: File, staging: File, seen: MutableSet<String>, checkCancelled: () -> Unit) {
        ZipFile(archive).use { zip ->
            val buffer = ByteArray(128 * 1024)
            for (entry in zip.entries()) {
                checkCancelled()
                require(seen.add(entry.name)) {
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
