package dev.renakoni.voicebox.core

import dev.renakoni.voicebox.bridge.GenerationContext
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val downloadClient by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
}
private val mirrorClient by lazy { downloadClient.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

data class ModelDownload(val url: String, val bytes: Long, val sha256: String, val mirrors: List<String> = emptyList())

data class ModelDownloadFile(val source: ModelDownload, val target: File)

/** Local copies of catalogued release assets can be staged before an offline install. */
fun modelImportDirectory(context: android.content.Context): File? =
    context.getExternalFilesDir(null)?.let { File(it, "VoiceBoxImports") }

fun stageModelFile(source: ModelDownload, target: File, imports: File?, checkCancelled: () -> Unit, progress: (Int) -> Unit) {
    val name = URL(source.url).path.substringAfterLast('/')
    val local = imports?.let { File(it, name) }
    if (local != null && local.isFile && local.length() == source.bytes) {
        checkCancelled()
        prepareDownloadTarget(target, source.bytes)
        if (local.inputStream().use { copyDownload(it, target, source.bytes, source.sha256, checkCancelled, progress) }) return
        // Release the rejected staged copy before checking free space for the online package.
        Files.delete(target.toPath())
    }
    downloadModelFile(source, target, checkCancelled, progress = progress)
}

private fun prepareDownloadTarget(target: File, bytes: Long) {
    val directory = requireNotNull(target.absoluteFile.parentFile)
    requireUi(directory.mkdirs() || directory.isDirectory) { UiText("download_directory_failed") }
    requireUi(directory.usableSpace >= bytes) { UiText("download_storage_full") }
}

/** Choose a reachable mirror once per host group, then transfer up to two missing files at a time. */
fun downloadModelFiles(files: List<ModelDownloadFile>, checkCancelled: () -> Unit,
    onInstalled: (ModelDownloadFile) -> Unit = {},
    progress: (completed: Int, total: Int, percent: Int) -> Unit) {
    if (files.isEmpty()) return
    val batch = GenerationContext()
    val cancelBatch = GenerationContext.current()?.onCancel { batch.cancel("CANCELLED") }
    val pool = Executors.newFixedThreadPool(2)
    var interrupted = false
    fun checkActive() {
        checkCancelled()
        batch.checkCancelled()
        if (Thread.currentThread().isInterrupted) throw CancellationException()
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
            batch.run {
                downloadModelFile(file.source, temporary, ::checkActive, routes[index]) { percent ->
                    synchronized(received) {
                        received[index] = file.source.bytes * percent / 100
                        publish()
                    }
                }
            }
            checkActive()
            Files.move(temporary.toPath(), file.target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            onInstalled(file)
            synchronized(received) { completed++; publish() }
        }) }
        repeat(files.size) { completion.take().get() }
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } catch (error: InterruptedException) {
        interrupted = true
        throw error
    } finally {
        cancelBatch?.close()
        batch.cancel("CANCELLED")
        pool.shutdownNow()
        // A retry must not clear this component while any of its writers are still running.
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
    prepareDownloadTarget(target, source.bytes)
    var failure: Exception? = null
    for (url in ordered) {
        checkCancelled()
        try {
            downloadFromUrl(url, checkCancelled) { input ->
                requireUi(copyDownload(input, target, source.bytes, source.sha256, checkCancelled, progress)) {
                    UiText("download_checksum_failed")
                }
            }
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
    val calls = urls.map { mirrorClient.newCall(Request.Builder().url(it).head().build()) }
    val pool = Executors.newFixedThreadPool(urls.size)
    val completion = ExecutorCompletionService<Int?>(pool)
    try {
        calls.forEachIndexed { index, call -> completion.submit(Callable {
            try { call.execute().use { if (it.isSuccessful) index else null } }
            catch (_: IOException) { null }
        }) }
        var remaining = calls.size
        while (remaining > 0) {
            checkCancelled()
            val response = completion.poll(100, TimeUnit.MILLISECONDS) ?: continue
            remaining--
            val winner = response.get()
            if (winner != null) return winner
        }
        return 0
    } finally {
        calls.forEach(Call::cancel)
        pool.shutdownNow()
    }
}

internal data class BridgePart(val url: String, val bytes: Long)

internal fun stageBridgePart(part: BridgePart, target: File, local: File?, checkCancelled: () -> Unit, progress: (Int) -> Unit) {
    prepareDownloadTarget(target, part.bytes)
    fun copy(input: java.io.InputStream) {
        requireUi(copyDownload(input, target, part.bytes, null, checkCancelled, progress)) { UiText("download_size_mismatch") }
    }
    if (local?.isFile == true) local.inputStream().use(::copy)
    else downloadFromUrl(part.url, checkCancelled, ::copy)
}

private fun downloadFromUrl(url: String, checkCancelled: () -> Unit, consume: (java.io.InputStream) -> Unit) {
    val request = Request.Builder().url(url).header("User-Agent", "VoiceBox/${BuildConfig.VERSION_NAME}").build()
    val call = downloadClient.newCall(request)
    val headers = CompletableFuture<Response>()
    val hook = GenerationContext.current()?.onCancel {
        // DNS can still be resolving. Release the installer and close any active socket off the UI thread.
        headers.cancel(false)
        downloadClient.dispatcher.executorService.execute(call::cancel)
    }
    var complete = false
    try {
        checkCancelled()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { headers.completeExceptionally(e) }
            override fun onResponse(call: Call, response: Response) {
                if (!headers.complete(response)) response.close()
            }
        })
        val response = try { headers.join() } catch (error: CompletionException) { throw error.cause ?: error }
        response.use {
            checkCancelled()
            requireUi(it.code == 200) { UiText("download_service_unavailable") }
            consume(it.body.byteStream())
        }
        complete = true
    } finally {
        hook?.close()
        if (!complete) call.cancel()
    }
}

private fun copyDownload(input: java.io.InputStream, target: File, bytes: Long, sha256: String?,
    checkCancelled: () -> Unit, progress: (Int) -> Unit): Boolean {
    val digest = sha256?.let { MessageDigest.getInstance("SHA-256") }
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
            requireUi(total <= bytes) { UiText("download_size_mismatch") }
            digest?.update(buffer, 0, count)
            output.write(buffer, 0, count)
            val percent = (total * 100 / bytes).toInt()
            if (percent != lastPercent) { progress(percent); lastPercent = percent }
        }
    }
    checkCancelled()
    return total == bytes && (digest == null || digest.digest().toHexString() == sha256)
}
