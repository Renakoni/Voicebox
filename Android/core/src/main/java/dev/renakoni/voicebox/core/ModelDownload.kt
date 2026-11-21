package dev.renakoni.voicebox.core

import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ModelDownload(val url: String, val bytes: Long, val sha256: String, val mirrors: List<String> = emptyList())

data class ModelDownloadFile(val source: ModelDownload, val target: File)

/** Choose a reachable mirror once per host group, then transfer up to two missing files at a time. */
fun downloadModelFiles(files: List<ModelDownloadFile>, checkCancelled: () -> Unit,
    onInstalled: (ModelDownloadFile) -> Unit = {},
    progress: (completed: Int, total: Int, percent: Int) -> Unit) {
    if (files.isEmpty()) return
    val pool = Executors.newFixedThreadPool(2)
    val stopped = AtomicBoolean()
    var interrupted = false
    fun checkActive() {
        checkCancelled()
        if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException()
    }
    try {
        val preferredMirrors = mutableMapOf<List<String>, Int>()
        val routes = files.map { file ->
            checkActive()
            val urls = listOf(file.source.url) + file.source.mirrors
            if (urls.size == 1) urls else {
                val index = preferredMirrors.getOrPut(urls.map { URL(it).authority }) { preferredMirror(urls, ::checkActive) }
                listOf(urls[index]) + urls.filterIndexed { i, _ -> i != index }
            }
        }
        checkActive()
        val received = LongArray(files.size)
        val totalBytes = files.sumOf { it.source.bytes }
        var completed = 0
        fun publish() = progress(completed, files.size,
            (received.sum() * 100 / totalBytes).toInt().coerceAtMost(if (completed == files.size) 100 else 99))
        publish()
        val completion = ExecutorCompletionService<Unit>(pool)
        files.forEachIndexed { index, file -> completion.submit(Callable {
            checkActive()
            val temporary = File(file.target.parentFile, ".${file.target.name}.download")
            try {
                downloadModelFile(file.source, temporary, ::checkActive, routes[index]) { percent ->
                    synchronized(received) {
                        received[index] = file.source.bytes * percent / 100
                        publish()
                    }
                }
                checkActive()
                Files.move(temporary.toPath(), file.target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                onInstalled(file)
                synchronized(received) { completed++; publish() }
            } finally { temporary.delete() }
        }) }
        repeat(files.size) { completion.take().get() }
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } catch (error: InterruptedException) {
        interrupted = true
        throw error
    } finally {
        stopped.set(true)
        pool.shutdownNow()
        // The installer may delete its staging directory only after all writers exit.
        while (true) {
            try { if (pool.awaitTermination(1, TimeUnit.SECONDS)) break }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/** Downloads are checked once while streaming; only verified files reach the installer. */
fun downloadModelFile(source: ModelDownload, target: File, checkCancelled: () -> Unit,
    ordered: List<String> = listOf(source.url) + source.mirrors,
    progress: (Int) -> Unit) {
    checkCancelled()
    val directory = requireNotNull(target.absoluteFile.parentFile)
    requireUi(directory.mkdirs() || directory.isDirectory) { UiText("download_directory_failed") }
    // All callers supply a scratch file. An interrupted transfer may still occupy its space.
    Files.deleteIfExists(target.toPath())
    requireUi(directory.usableSpace >= source.bytes) { UiText("download_storage_full") }
    var failure: Exception? = null
    for (url in ordered) {
        checkCancelled()
        try {
            downloadFromUrl(source, url, target, checkCancelled, progress)
            return
        } catch (error: Exception) {
            checkCancelled()
            if (error is java.util.concurrent.CancellationException) throw error
            failure = error
        }
    }
    throw requireNotNull(failure)
}

private fun preferredMirror(urls: List<String>, checkCancelled: () -> Unit): Int {
    val pool = Executors.newFixedThreadPool(urls.size)
    val completion = ExecutorCompletionService<Int?>(pool)
    val connections = java.util.Collections.synchronizedList(mutableListOf<HttpURLConnection>())
    try {
        urls.forEachIndexed { index, url -> completion.submit(Callable {
            val connection = URL(url).openConnection() as HttpURLConnection
            connections.add(connection)
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.requestMethod = "HEAD"
                if (connection.responseCode in 200..299) index else null
            } catch (_: Exception) { null }
            finally { connection.disconnect() }
        }) }
        repeat(urls.size) {
            checkCancelled()
            val winner = completion.take().get()
            if (winner != null) return winner
        }
        return 0
    } finally {
        pool.shutdownNow()
        synchronized(connections) { connections.forEach(HttpURLConnection::disconnect) }
    }
}

private fun downloadFromUrl(source: ModelDownload, url: String, target: File, checkCancelled: () -> Unit,
    progress: (Int) -> Unit) {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", "VoiceBox/${BuildConfig.VERSION_NAME}")
    var complete = false
    try {
        checkCancelled()
        requireUi(connection.responseCode == HttpURLConnection.HTTP_OK) { UiText("download_service_unavailable") }
        connection.inputStream.use { copyVerifiedDownload(source, it, target, checkCancelled, progress) }
        complete = true
    } finally {
        connection.disconnect()
        if (!complete) target.delete()
    }
}

fun copyVerifiedDownload(source: ModelDownload, input: java.io.InputStream, target: File,
    checkCancelled: () -> Unit, progress: (Int) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    var lastPercent = 0
    progress(0)
    target.outputStream().buffered().use { output ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            requireUi(total <= source.bytes) { UiText("download_size_mismatch") }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            val percent = (total * 100 / source.bytes).toInt()
            if (percent != lastPercent) { progress(percent); lastPercent = percent }
        }
    }
    checkCancelled()
    val hash = digest.digest().toHexString()
    requireUi(total == source.bytes && hash == source.sha256) { UiText("download_checksum_failed") }
}
