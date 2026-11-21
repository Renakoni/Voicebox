package dev.renakoni.voicebox.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BridgePackageRemovalTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = File(folder.root, "model-runtimes/gptsovits")

    @Test fun bundledBridgeStaysRemovedAtStartupUntilManuallyReinstalled() {
        val archive = bridge()
        assertTrue(archive.install({}, automatic = true) {})
        val previous = archive.writableFixtureDirectory()
        val sibling = write("model-runtimes/ming/active.json")

        archive.uninstall()
        // Simulate files left behind by an interrupted cleanup.
        val leftover = write("model-runtimes/gptsovits/.stage-interrupted/file")

        val reopened = bridge()
        assertFalse(reopened.install({}, automatic = true) {})
        assertNull(reopened.active)
        assertFalse(previous.exists())
        assertFalse(leftover.exists())
        assertTrue(sibling.isFile)
        assertTrue(reopened.install({}) {})
        assertTrue(reopened.writableFixtureDirectory().isDirectory)
    }

    @Test fun uninstallDoesNotFollowLinksToOtherModelFiles() {
        val archive = bridge()
        assertTrue(archive.install({}) {})
        val directory = archive.writableFixtureDirectory()
        val voice = write("CharacterModels/v2/personal/weights.bin")
        val link = File(directory, "external-model").toPath()
        try {
            Files.createSymbolicLink(link, voice.toPath().parent)
        } catch (error: IOException) {
            assumeNoException("The test host must support creating symbolic links", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }

        try {
            archive.uninstall()
            assertTrue(voice.isFile)
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        } finally {
            Files.deleteIfExists(link)
        }
    }

    private fun write(path: String) = File(folder.root, path).apply {
        parentFile!!.mkdirs()
        writeText("fixture")
    }

    private fun BridgePackage.writableFixtureDirectory(): File {
        val installed = requireNotNull(active)
        // Windows requires clearing its read-only attribute before unlinking a test file.
        check(File(installed.directory, installed.spec.code).setWritable(true))
        return installed.directory
    }

    private fun bridge(): BridgePackage {
        val manifest = JSONObject().put("id", "gptsovits").put("contract", 1)
            .put("entry", "example.Provider").put("abi", "x86_64").put("code", "backend.jar")
            .put("sourceCommit", "a".repeat(40)).put("adapterSourceSha256", "b".repeat(64))
        val manifestBytes = manifest.toString().encodeToByteArray()
        val code = byteArrayOf(1)
        val part = JSONObject().put("url", "asset://backend-packages/model.zip").put("bytes", 1).put("sha256", "c".repeat(64))
        val catalog = JSONObject(manifest.toString()).put("format", 2).put("version", 3)
            .put("revision", "d".repeat(64)).put("parts", JSONArray().put(part))
            .put("files", JSONObject().put("bridge.json", manifestBytes.size).put("backend.jar", code.size))
        return BridgePackage(root, BridgePackageSpec(catalog), "x86_64", 11) { _, target, _, _ ->
            ZipOutputStream(target.outputStream()).use { zip ->
                for ((name, bytes) in mapOf("bridge.json" to manifestBytes, "backend.jar" to code)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }
}
