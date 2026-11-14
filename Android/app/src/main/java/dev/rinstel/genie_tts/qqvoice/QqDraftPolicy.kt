package dev.rinstel.genie_tts.qqvoice

import java.security.MessageDigest

data class QqDraftFile(val path: String, val size: Long, val modified: Long)

object QqDraftPolicy {
    const val ROOT = "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ"
    private val pathPattern = Regex("${Regex.escape(ROOT)}/[0-9]{4,12}/ptt/[0-9]{6}/[0-9]{2}/stream_[0-9]{16}\\.slk")
    fun validPath(path: String) = pathPattern.matches(path)
    fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun select(before: List<QqDraftFile>, after: List<QqDraftFile>, started: Long, now: Long): QqDraftFile {
        check(now >= started && now - started <= 180) { "准备已过期，请取消 QQ 草稿后重新开始。" }
        val existing = before.map { it.path }.toSet()
        val candidates = after.filter { validPath(it.path) && it.path !in existing && it.modified >= started && it.size in 11..512000 }
        check(candidates.size == 1) { "未找到唯一的新录音。请只在目标会话录一段未发送的草稿。" }
        return candidates.single()
    }
}
