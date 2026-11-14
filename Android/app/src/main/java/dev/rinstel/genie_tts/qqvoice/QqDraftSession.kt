package dev.rinstel.genie_tts.qqvoice

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Temporary state for one automatic send. No microphone recording is backed up. */
class QqDraftSession(context: Context) {
    private val directory = File(context.noBackupFilesDir, "qq-draft").apply { mkdirs() }
    private val record = AtomicFile(File(directory, "request.json"))
    private val silk = File(directory, "voice.silk")
    fun status(): JSONObject? = if (record.baseFile.exists()) record.openRead().use { JSONObject(it.reader().readText()) } else null

    private fun save(value: JSONObject) {
        val out = record.startWrite()
        try { out.write(value.toString().toByteArray()); record.finishWrite(out) }
        catch (e: Exception) { record.failWrite(out); throw e }
    }

    fun prepare(wav: File) {
        check(status() == null) { "上一条语音仍在处理中。" }
        val encoded = QqSilk.fromWav(wav)
        silk.writeBytes(encoded.bytes)
        save(JSONObject().put("phase", "prepared").put("durationMs", encoded.durationMs))
    }

    fun beginSelection() {
        val request = requireNotNull(status())
        check(request.getString("phase") in listOf("prepared", "choosing"))
        request.remove("recipient")
        save(request.put("phase", "choosing"))
    }

    fun selectRecipient(name: String) {
        val request = requireNotNull(status())
        check(request.getString("phase") == "choosing")
        require(name.isNotBlank())
        save(request.put("recipient", name).put("phase", "prepared"))
    }

    fun beginSend(adb: LocalAdb, recipient: String) {
        val request = requireNotNull(status())
        check(request.getString("phase") == "replaced" && request.optString("recipient") == recipient)
        check(QqDraftPolicy.sha256(adb.readFile(request.getString("path"))) == request.getString("replacementHash")) {
            "草稿内容已变化，未点击发送。请在 QQ 检查。"
        }
        // Persist before the tap: a lost ADB reply or process restart must never resend.
        save(request.put("phase", "sending"))
    }

    fun submitted() {
        val request = requireNotNull(status())
        check(request.getString("phase") == "sending")
        save(request.put("phase", "submitted"))
    }

    private fun scan(adb: LocalAdb): List<QqDraftFile> {
        // QQ's outgoing draft files use stream_*; received and historical cache files are excluded.
        val date = adb.shell("date +%Y%m/%d")
        check(date.matches(Regex("[0-9]{6}/[0-9]{2}")))
        val result = adb.shell("for f in ${QqDraftPolicy.ROOT}/*/ptt/$date/stream_*.slk; do [ -f \"\$f\" ] && stat -c '%n|%s|%Y' \"\$f\"; done; true")
        return result.lineSequence().filter { it.isNotBlank() }.map {
            val fields = it.split('|')
            check(fields.size == 3 && QqDraftPolicy.validPath(fields[0])) { "QQ 目录格式不兼容。" }
            QqDraftFile(fields[0], fields[1].toLong(), fields[2].toLong())
        }.toList()
    }

    fun arm(adb: LocalAdb) {
        val request = requireNotNull(status())
        check(request.getString("phase") == "prepared")
        // An empty glob cannot distinguish an unused directory from denied access.
        adb.shell("ls ${QqDraftPolicy.ROOT} >/dev/null")
        val before = scan(adb)
        val rows = JSONArray()
        before.forEach { rows.put(JSONObject().put("path", it.path).put("size", it.size).put("modified", it.modified)) }
        request.put("before", rows).put("started", adb.shell("date +%s").toLong()).put("phase", "armed")
        save(request)
    }

    fun replace(adb: LocalAdb) {
        val request = requireNotNull(status())
        check(request.getString("phase") == "armed") { "此草稿已经处理，请勿重复替换。" }
        val rows = request.getJSONArray("before")
        val before = (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            QqDraftFile(row.getString("path"), row.getLong("size"), row.getLong("modified")) } }
        val candidate = QqDraftPolicy.select(before, scan(adb), request.getLong("started"), adb.shell("date +%s").toLong())
        Thread.sleep(600)
        check(scan(adb).find { it.path == candidate.path } == candidate) { "录音仍在写入，请在 QQ 停止录音后重试。" }
        val current = adb.readFile(candidate.path)
        check(current.size.toLong() == candidate.size && current.take(10).toByteArray().contentEquals(byteArrayOf(2) + "#!SILK_V3".toByteArray())) {
            "新录音格式或大小不符，已停止。"
        }
        request.put("path", candidate.path)
            .put("replacementHash", QqDraftPolicy.sha256(silk.readBytes())).put("phase", "writing")
        save(request) // A killed process must not silently retry a possibly completed replacement.
        guardedWrite(adb, candidate.path, silk.readBytes(), QqDraftPolicy.sha256(current))
        request.put("phase", "replaced")
        save(request)
    }

    private fun guardedWrite(adb: LocalAdb, path: String, data: ByteArray, expectedHash: String) {
        require(QqDraftPolicy.validPath(path))
        val temporary = "$path.genie-${UUID.randomUUID()}.tmp"
        try {
            adb.writeFile(temporary, data)
            val hash = adb.shell("sha256sum ${LocalAdb.quote(path)}").substringBefore(' ')
            check(hash == expectedHash) { "草稿已改变，未覆盖。" }
            val owner = adb.shell("stat -c %u ${LocalAdb.quote(path)}")
            // Renaming a shell-owned temp file makes the draft unreadable by QQ.
            // Preserve the existing inode/owner. A failed write must never reach Send.
            adb.shell("cat ${LocalAdb.quote(temporary)} > ${LocalAdb.quote(path)}")
            check(adb.shell("stat -c %u ${LocalAdb.quote(path)}") == owner) { "草稿属主发生变化，已停止。" }
            check(QqDraftPolicy.sha256(adb.readFile(path)) == QqDraftPolicy.sha256(data)) { "音频校验失败，未发送。请在 QQ 取消该草稿。" }
        } finally { runCatching { adb.shell("rm -f ${LocalAdb.quote(temporary)}") } }
    }

    fun finish() {
        record.delete()
        silk.delete()
        File(directory, "original.slk").delete() // Remove backups left by older installed versions.
    }
}
