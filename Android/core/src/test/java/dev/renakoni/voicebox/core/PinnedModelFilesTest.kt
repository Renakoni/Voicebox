package dev.renakoni.voicebox.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class PinnedModelFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importedFileIsVerifiedOnceAndSameSizeChangesInvalidateTheReceipt() {
        val store = PinnedModelFiles(folder.root, File(folder.root, "locks"))
        val file = folder.newFile("weights")
        file.writeText("v001")
        val pin = FilePin(4, digest("v001"))
        assertTrue(store.matches(file, pin) {})
        assertTrue(store.matches(file, pin) { fail("A verified file was read again") })
        file.writeText("oops")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 3000))
        assertFalse(store.matches(file, pin) {})
        assertTrue(store.matches(file, FilePin(4, digest("oops"))) { fail("Changing the pin reread the same file") })
    }

    @Test fun partialInstallAndRetryRequestOnlyMissingOrChangedPins() {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { request ->
            val data = (if (hits.incrementAndGet() == 1) "bad!" else "next").toByteArray()
            request.sendResponseHeaders(200, data.size.toLong())
            request.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val store = PinnedModelFiles(folder.root, File(folder.root, "locks"))
            val url = "http://127.0.0.1:${server.address.port}/asset"
            val common = ModelDownloadFile(ModelDownload(url, 4, digest("base")), folder.newFile("common"))
            common.target.writeText("base")
            val extra = ModelDownloadFile(ModelDownload(url, 4, digest("next")), folder.newFile("vocoder"))
            extra.target.writeText("prev")
            val both = listOf(common, extra)
            assertThrows(IllegalArgumentException::class.java) { store.download(both, {}) { _, _, _ -> } }
            assertEquals("prev", extra.target.readText())
            store.download(both, {}) { _, _, _ -> }
            store.download(both, {}) { _, _, _ -> }
            assertEquals(2, hits.get())
            assertEquals("next", extra.target.readText())
        } finally { server.stop(0) }
    }

    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
}
