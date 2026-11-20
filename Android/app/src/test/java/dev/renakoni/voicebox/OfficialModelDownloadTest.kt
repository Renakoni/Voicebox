package dev.renakoni.voicebox

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OfficialModelDownloadTest {
    @Test fun fallsBackWhenTheFastestMirrorReturnsCorruptData() {
        val body = ByteArray(4096) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val corruptDownloads = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/corrupt") { response ->
            if (response.requestHeaders.getFirst("Range") == null) corruptDownloads.incrementAndGet()
            response.sendResponseHeaders(200, body.size.toLong())
            response.responseBody.use { it.write(ByteArray(body.size)) }
        }
        server.createContext("/backup") { response ->
            // A source can support full downloads even when its range probe fails.
            if (response.requestHeaders.getFirst("Range") != null) {
                response.sendResponseHeaders(503, -1)
                response.close()
            } else {
                response.sendResponseHeaders(200, body.size.toLong())
                response.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val root = Files.createTempDirectory("official-fallback").toFile()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val source = ModelDownload("$base/corrupt", body.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) },
                listOf("$base/backup"))
            val target = root.resolve("model.pth")
            downloadModelFiles(listOf(ModelDownloadFile(source, target)), {}) { _, _, _ -> }
            assertEquals(1, corruptDownloads.get())
            assertArrayEquals(body, target.readBytes())
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun verifiesBytesAndRemovesCorruptOrCancelledDownloads() {
        val body = ByteArray(4096) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model") { response ->
            response.sendResponseHeaders(200, body.size.toLong())
            response.responseBody.use { it.write(body) }
        }
        server.start()
        val root = Files.createTempDirectory("official-download").toFile()
        val target = root.resolve("model.pth")
        val source = ModelDownload("http://127.0.0.1:${server.address.port}/model", body.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) })
        try {
            val progress = mutableListOf<Int>()
            downloadModelFile(source, target, {}) { progress += it }
            assertArrayEquals(body, target.readBytes())
            assertEquals(100, progress.last())
            for (invalid in listOf(source.copy(sha256 = "0".repeat(64)), source.copy(bytes = source.bytes + 1))) {
                assertThrows(IllegalArgumentException::class.java) { downloadModelFile(invalid, target, {}) {} }
                assertFalse(target.exists())
            }
            var checks = 0
            assertThrows(CancellationException::class.java) {
                downloadModelFile(source, target, { if (++checks > 1) throw CancellationException() }) {}
            }
            assertFalse(target.exists())
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun probesTheWholeQueueOnceAndDownloadsTwoFilesConcurrently() {
        val body = ByteArray(4096) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val handlers = Executors.newCachedThreadPool()
        val caller = Executors.newSingleThreadExecutor()
        server.executor = handlers
        val probes = List(3) { AtomicInteger() }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val allProbedFirst = AtomicBoolean(true)
        val twoStarted = CountDownLatch(2)
        val release = CountDownLatch(1)
        server.createContext("/model") { response ->
            val index = response.requestURI.path.substringAfterLast('/').toInt()
            val probe = response.requestHeaders.getFirst("Range") != null
            if (probe) probes[index].incrementAndGet()
            else {
                if (probes.any { it.get() != 1 }) allProbedFirst.set(false)
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                started.incrementAndGet()
                twoStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            try {
                response.sendResponseHeaders(200, body.size.toLong())
                response.responseBody.use { it.write(body) }
            } finally { if (!probe) active.decrementAndGet(); response.close() }
        }
        server.createContext("/backup") { response -> response.sendResponseHeaders(503, -1); response.close() }
        server.start()
        val root = Files.createTempDirectory("parallel-download").toFile()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
            val files = List(3) { index -> ModelDownloadFile(
                ModelDownload("$base/model/$index", body.size.toLong(), hash, listOf("$base/backup/$index")),
                root.resolve("nested/model-$index.pth")) }
            val progress = mutableListOf<Triple<Int, Int, Int>>()
            val task = caller.submit {
                downloadModelFiles(files, {}) { completed, total, percent -> progress += Triple(completed, total, percent) }
            }
            assertTrue(twoStarted.await(10, TimeUnit.SECONDS))
            assertEquals(2, started.get())
            assertFalse(task.isDone)
            release.countDown()
            task.get(10, TimeUnit.SECONDS)
            assertTrue(allProbedFirst.get())
            assertEquals(listOf(1, 1, 1), probes.map { it.get() })
            assertEquals(2, peak.get())
            files.forEach { assertArrayEquals(body, it.target.readBytes()) }
            assertEquals(Triple(3, 3, 100), progress.last())
            assertFalse(root.walkTopDown().any { it.name.endsWith(".download") })
        } finally {
            release.countDown(); caller.shutdownNow(); caller.awaitTermination(20, TimeUnit.SECONDS)
            server.stop(0); handlers.shutdownNow(); root.deleteRecursively()
        }
    }

    @Test fun cancellationWaitsForBothWritersAndKeepsPreviouslyInstalledFiles() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val handlers = Executors.newCachedThreadPool()
        val caller = Executors.newSingleThreadExecutor()
        server.executor = handlers
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        server.createContext("/model") { response ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            response.sendResponseHeaders(200, 4096)
            response.responseBody.use { it.write(ByteArray(4096)) }
        }
        server.start()
        val root = Files.createTempDirectory("cancel-parallel-download").toFile()
        try {
            val files = List(2) { index -> ModelDownloadFile(
                ModelDownload("http://127.0.0.1:${server.address.port}/model/$index", 4096, "0".repeat(64)),
                root.resolve("model-$index.pth").apply { writeText("previous") }) }
            val task = caller.submit {
                downloadModelFiles(files, { if (cancelled.get()) throw CancellationException() }) { _, _, _ -> }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            cancelled.set(true)
            release.countDown()
            val error = assertThrows(ExecutionException::class.java) { task.get(10, TimeUnit.SECONDS) }
            assertTrue(error.cause is CancellationException)
            files.forEach { assertEquals("previous", it.target.readText()) }
            assertEquals(2, root.listFiles()!!.size)
        } finally {
            release.countDown(); caller.shutdownNow(); caller.awaitTermination(20, TimeUnit.SECONDS)
            server.stop(0); handlers.shutdownNow(); root.deleteRecursively()
        }
    }
}
