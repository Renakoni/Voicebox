package dev.renakoni.voicebox.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BridgePackageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manifest = JSONObject().put("id", "sample").put("contract", 1).put("entry", "example.Entry")
        .put("adapterSourceSha256", "e".repeat(64)).put("sourceCommit", "d".repeat(40)).put("code", "tasks.jar").put("abi", "arm64-v8a")
    private val payload get() = mapOf("bridge.json" to manifest.toString(), "tasks.jar" to "abc", "libcodec.so" to "def")
    private fun archive(files: Map<String, String>) = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
    }
    private fun catalog(zip: File): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        return JSONObject(manifest.toString()).put("format", 2).put("revision", "a".repeat(64))
            .put("files", JSONObject(payload.mapValues { it.value.length }))
            .put("parts", JSONArray().put(JSONObject().put("url", "asset://sample.zip")
                .put("bytes", zip.length()).put("sha256", digest)))
    }
    private fun repository(zip: File, spec: JSONObject = catalog(zip), root: File = temporary.newFolder(), abi: String = "arm64-v8a") =
        BridgePackage(root, BridgePackageSpec(spec), abi) { source, target, cancel, progress ->
            zip.inputStream().use { copyVerifiedDownload(source, it, target, cancel, progress) }
        }

    @Test fun rejectsTraversalUnknownTruncatedAndOversizedEntries() {
        for (name in listOf("../escape", "unlisted.so", "tasks.jar")) {
            val zip = archive(mapOf(name to "abcd"))
            val repository = repository(zip)
            assertThrows(IllegalArgumentException::class.java) { repository.install({}, {}) }
            assertFalse(repository.installed)
        }
        val repository = repository(archive(mapOf("tasks.jar" to "abc")))
        assertThrows(IllegalArgumentException::class.java) { repository.install({}, {}) }
        assertFalse(repository.installed)
    }

    @Test fun rejectsDuplicatePathsAcrossParts() {
        val zip = archive(payload)
        val spec = catalog(zip)
        spec.getJSONArray("parts").apply { put(getJSONObject(0)) }
        val repository = repository(zip, spec)
        assertThrows(IllegalArgumentException::class.java) { repository.install({}, {}) }
        assertFalse(repository.installed)
    }

    @Test fun rejectsWrongHashAbiAndContract() {
        val zip = archive(payload)
        val spec = catalog(zip)
        spec.getJSONArray("parts").getJSONObject(0).put("sha256", "0".repeat(64))
        val badHash = repository(zip, spec)
        assertThrows(IllegalArgumentException::class.java) { badHash.install({}, {}) }
        assertFalse(badHash.installed)
        val wrongAbi = repository(zip, abi = "x86_64")
        assertThrows(IllegalArgumentException::class.java) { wrongAbi.install({}, {}) }
        assertFalse(wrongAbi.installed)
        assertThrows(IllegalArgumentException::class.java) { BridgePackageSpec(catalog(zip).put("contract", 42)) }
        val mismatch = repository(zip, catalog(zip).put("entry", "example.Other"))
        assertThrows(IllegalArgumentException::class.java) { mismatch.install({}, {}) }
        assertFalse(mismatch.installed)
    }

    @Test fun interruptedInstallCanRetryAndReopeningDoesNotExtractAgain() {
        val zip = archive(payload)
        val root = temporary.newFolder()
        val repository = repository(zip, root = root)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            repository.install({ if (++checks == 5) throw CancellationException() }, {})
        }
        assertFalse(repository.installed)
        assertEquals(setOf(".lock"), root.list()!!.toSet())
        repository.install({}) { if (it < 100) assertFalse(repository.installed) }
        val previouslyInstalled = repository.directory
        val reopened = repository(zip, root = root)
        reopened.install({}) { fail("Installed packages should not extract again") }
        assertEquals(previouslyInstalled, reopened.directory)
    }

    @Test fun failedNewRevisionLeavesExistingRuntimeAvailable() {
        val root = temporary.newFolder()
        val zip = archive(payload)
        val old = repository(zip, root = root)
        old.install({}, {})
        val active = old.directory
        val invalid = catalog(zip).put("revision", "c".repeat(64))
        invalid.getJSONArray("parts").getJSONObject(0).put("sha256", "f".repeat(64))
        val update = repository(zip, invalid, root)
        assertThrows(IllegalArgumentException::class.java) { update.install({}, {}) }
        assertFalse(update.installed)
        assertEquals(active, old.directory)
        assertFalse(root.list().orEmpty().any { it.startsWith(".stage-") || it == ".download" })
    }

    @Test fun repairPublishesANewDirectoryAndPreservesThePreviousSnapshot() {
        val zip = archive(payload)
        val repository = repository(zip)
        repository.install({}, {})
        val previous = repository.directory!!
        File(previous, "libcodec.so").writeText("truncated")
        assertFalse(repository.installed)
        repository.install({}, {})
        assertNotEquals(previous, repository.directory)
        assertEquals("abc", File(previous, "tasks.jar").readText())
        assertEquals("def", File(repository.directory!!, "libcodec.so").readText())
    }
}
