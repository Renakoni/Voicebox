package dev.renakoni.voicebox.model.ming

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MingPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val required = listOf("tasks.jar", "libonnxruntime.so", "libming-synthesize.so", "libming-reference.so")
    private fun spec() = MingPackageSpec(JSONObject().put("format", 1).put("id", MingModel.ID)
        .put("revision", "a".repeat(64)).put("abi", "arm64-v8a")
        .put("parts", JSONArray().put(JSONObject().put("url", "https://example.com/ming.zip")
            .put("bytes", 200).put("sha256", "b".repeat(64))))
        .put("files", JSONObject(required.associateWith { 3L })))
    private fun archive(name: String, contents: String = "abc"): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(contents.toByteArray()); zip.closeEntry()
        }
    }

    @Test fun rejectsTraversalUnknownFilesAndOversizedFiles() {
        val repository = MingPackageRepository(temporary.newFolder(), spec())
        for ((name, value) in listOf("../escape" to "abc", "unlisted.so" to "abc", "tasks.jar" to "abcd")) {
            assertThrows(IllegalArgumentException::class.java) {
                repository.extract(archive(name, value), temporary.newFolder(), mutableSetOf()) {}
            }
        }
    }

    @Test fun rejectsDuplicatePathsAcrossSeparateReleaseParts() {
        val repository = MingPackageRepository(temporary.newFolder(), spec())
        val stage = temporary.newFolder()
        val seen = mutableSetOf<String>()
        repository.extract(archive("tasks.jar"), stage, seen) {}
        assertThrows(IllegalArgumentException::class.java) {
            repository.extract(archive("tasks.jar"), stage, seen) {}
        }
        assertEquals("abc", File(stage, "tasks.jar").readText())
    }

    @Test fun cancelledExtractionNeverAppearsInstalled() {
        val repository = MingPackageRepository(temporary.newFolder(), spec())
        assertThrows(CancellationException::class.java) {
            repository.extract(archive("tasks.jar"), temporary.newFolder(), mutableSetOf()) { throw CancellationException() }
        }
        assertFalse(repository.installed)
    }

    @Test fun completionMarkerAloneDoesNotMakeIncompleteRuntimeAvailable() {
        val repository = MingPackageRepository(temporary.newFolder(), spec())
        repository.directory.mkdirs()
        File(repository.directory, ".complete").writeText(repository.spec.revision)
        assertFalse(repository.installed)
        required.forEach { name -> File(repository.directory, name).apply { parentFile!!.mkdirs(); writeText("abc") } }
        assertTrue(repository.installed)
        File(repository.directory, "tasks.jar").writeText("truncated")
        assertFalse(repository.installed)
    }

    @Test fun installsAllFilesBeforePublishingAndCleansStaging() {
        val archive = temporary.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            required.forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write("abc".toByteArray()); zip.closeEntry()
            }
        }
        val root = temporary.newFolder()
        val repository = MingPackageRepository(root, spec()) { _, target, _, _ -> archive.copyTo(target) }
        repository.install({}) { progress -> if (progress < 100) assertFalse(repository.installed) }
        assertTrue(repository.installed)
        assertEquals(listOf(repository.spec.revision), root.listFiles()!!.map { it.name })
    }

    @Test fun failedDownloadLeavesPreviousRevisionIntactAndRemovesPartialFiles() {
        val root = temporary.newFolder()
        val previous = File(root, "previous-revision").apply { mkdirs() }
        File(previous, "keep").writeText("previous runtime")
        val repository = MingPackageRepository(root, spec()) { _, target, _, _ ->
            target.writeText("partial download")
            throw CancellationException()
        }
        assertThrows(CancellationException::class.java) { repository.install({}, {}) }
        assertFalse(repository.installed)
        assertEquals("previous runtime", File(previous, "keep").readText())
        assertEquals(listOf(previous.name), root.listFiles()!!.map { it.name })
    }
}
