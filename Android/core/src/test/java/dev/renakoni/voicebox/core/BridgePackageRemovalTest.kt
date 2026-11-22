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

class BridgePackageRemovalTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = File(folder.root, "model-runtimes/gptsovits")

    @Test fun uninstallDoesNotFollowLinksToOtherModelFiles() {
        val archive = bridge()
        val directory = requireNotNull(archive.active).directory
        val sibling = write("model-runtimes/ming/active.json")
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
            assertNull(archive.active)
            assertFalse(directory.exists())
            assertTrue(sibling.isFile)
            assertTrue(voice.isFile)
        } finally {
            Files.deleteIfExists(link)
        }
    }

    private fun write(path: String) = File(folder.root, path).apply {
        parentFile!!.mkdirs()
        writeText("fixture")
    }

    private fun bridge(): BridgePackage {
        val manifest = JSONObject().put("id", "gptsovits").put("contract", 1)
            .put("entry", "example.Provider").put("abi", "x86_64").put("code", "backend.jar")
            .put("sourceCommit", "a".repeat(40)).put("adapterSourceSha256", "b".repeat(64))
        val part = JSONObject().put("url", "https://example.test/model.zip").put("bytes", 1).put("sha256", "c".repeat(64))
        val revision = "d".repeat(64)
        val spec = BridgePackageSpec(manifest.put("format", 2).put("revision", revision).put("parts", JSONArray().put(part))
            .put("files", JSONObject().put("bridge.json", 1).put("backend.jar", 1)))
        write("model-runtimes/gptsovits/$revision/installed/backend.jar")
        File(root, "active.json").writeText(JSONObject().put("revision", revision).put("directory", "installed").toString())
        return BridgePackage(root, spec, "x86_64", 11)
    }
}
