package dev.renakoni.voicebox.qqvoice

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.checkUi

import java.security.MessageDigest

data class QqDraftFile(val path: String, val size: Long, val modified: Long)

object QqDraftPolicy {
    const val ROOT = "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ"
    private val pathPattern = Regex("${Regex.escape(ROOT)}/[0-9]{4,12}/ptt/[0-9]{6}/[0-9]{2}/stream_[0-9]{16}\\.slk")
    fun validPath(path: String) = pathPattern.matches(path)
    fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun select(before: List<QqDraftFile>, after: List<QqDraftFile>, started: Long, now: Long): QqDraftFile {
        checkUi(now >= started && now - started <= 180) { UiText(R.string.qq_draft_expired) }
        val existing = before.map { it.path }.toSet()
        val candidates = after.filter { validPath(it.path) && it.path !in existing && it.modified >= started && it.size in 11..512000 }
        checkUi(candidates.size == 1) { UiText(R.string.qq_draft_ambiguous) }
        return candidates.single()
    }
}
