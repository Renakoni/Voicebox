package dev.renakoni.voicebox.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

data class FilePin(val bytes: Long, val sha256: String)

val ModelDownload.pin get() = FilePin(bytes, sha256)

fun File.regularFileAttributes(): BasicFileAttributes? = try {
    Files.readAttributes(toPath(), BasicFileAttributes::class.java).takeIf { it.isRegularFile }
} catch (_: java.nio.file.NoSuchFileException) { null }

fun BasicFileAttributes.fileStamp() = "${size()} ${lastModifiedTime()} ${fileKey()}"

/** Receipts live outside importable runtime directories. Only the installer writes them. */
class PinnedModelFiles(private val root: File, private val receipts: File) {
    fun receipt(file: File) = File(receipts, file.relativeTo(root).path + ".lock")

    fun matches(file: File, pin: FilePin, cancel: () -> Unit) = matches(file, listOf(pin), cancel)

    fun matches(file: File, pins: List<FilePin>, cancel: () -> Unit): Boolean {
        val attrs = file.regularFileAttributes() ?: return false
        val candidates = pins.filter { it.bytes == attrs.size() }
        if (candidates.isEmpty()) return false
        val before = attrs.fileStamp()
        val lock = receipt(file)
        val saved = try { Files.readAllBytes(lock.toPath()).decodeToString() }
            catch (_: java.nio.file.NoSuchFileException) { null }
        if (saved != null && saved.substringBefore('\n') == before) {
            val actual = saved.substringAfter('\n')
            return candidates.any { it.sha256 == actual }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = try { Files.newInputStream(file.toPath()) }
            catch (_: java.nio.file.NoSuchFileException) { return false }
        stream.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                cancel()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (file.regularFileAttributes()?.fileStamp() != before) return false
        val actual = digest.digest().toHexString()
        write(lock, "$before\n$actual")
        return candidates.any { it.sha256 == actual }
    }

    fun record(file: File, pin: FilePin) {
        val attrs = requireNotNull(file.regularFileAttributes())
        require(attrs.size() == pin.bytes)
        write(receipt(file), "${attrs.fileStamp()}\n${pin.sha256}")
    }

    private fun write(lock: File, value: String) {
        lock.parentFile!!.mkdirs()
        val temp = File.createTempFile("receipt-", ".tmp", lock.parentFile)
        try {
            temp.writeText(value)
            Files.move(temp.toPath(), lock.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun download(files: List<ModelDownloadFile>, cancel: () -> Unit, progress: (Int, Int, Int) -> Unit) {
        val missing = files.filterNot { matches(it.target, it.source.pin, cancel) }
        downloadMissing(missing, cancel, progress)
    }

    fun downloadMissing(missing: List<ModelDownloadFile>, cancel: () -> Unit, progress: (Int, Int, Int) -> Unit) {
        downloadModelFiles(missing, cancel, onInstalled = { record(it.target, it.source.pin) }, progress = progress)
    }
}
