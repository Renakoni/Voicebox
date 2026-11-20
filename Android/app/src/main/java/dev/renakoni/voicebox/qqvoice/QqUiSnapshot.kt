package dev.renakoni.voicebox.qqvoice

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.checkUi

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.math.roundToInt

internal data class QqUiNode(val id: String, val text: String, val description: String,
    val x: Int, val y: Int, val clickable: Boolean)

/** Read only QQ controls; message text never identifies a recipient or a button. */
internal class QqUiSnapshot(xml: String) {
    private val nodes: List<QqUiNode>
    init {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val elements = document.getElementsByTagName("node")
        nodes = (0 until elements.length).mapNotNull { index ->
            val e = elements.item(index) as org.w3c.dom.Element
            if (e.getAttribute("package") != "com.tencent.mobileqq" || e.getAttribute("enabled") != "true") return@mapNotNull null
            val bounds = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matchEntire(e.getAttribute("bounds")) ?: return@mapNotNull null
            val (left, top, right, bottom) = bounds.groupValues.drop(1).map(String::toInt)
            if (right <= left || bottom <= top) return@mapNotNull null
            QqUiNode(e.getAttribute("resource-id"), e.getAttribute("text"), e.getAttribute("content-desc"),
                (left + right) / 2, (top + bottom) / 2, e.getAttribute("clickable") == "true")
        }
    }

    private fun unique(predicate: (QqUiNode) -> Boolean): QqUiNode? {
        val matches = nodes.filter(predicate)
        checkUi(matches.size <= 1) { UiText(R.string.qq_duplicate_controls) }
        return matches.singleOrNull()
    }
    fun conversationName() = unique { it.id == "com.tencent.mobileqq:id/3o4" }?.text?.takeIf { it.isNotBlank() }
    fun requireConversation(name: String) {
        checkUi(conversationName() == name) { UiText(R.string.qq_conversation_changed) }
    }
    fun isConversationList() = conversationName() == null &&
        nodes.any { it.id == "com.tencent.mobileqq:id/aua" && it.clickable }
    fun voiceButton() = unique { it.clickable && it.description == "语音" }
    fun recordTab() = unique { it.id == "com.tencent.mobileqq:id/i3t" && it.text == "录音" && it.clickable }
    fun recordButton() = unique { it.id == "com.tencent.mobileqq:id/i40" && it.description == "开始录音" && it.clickable }
    fun hasActiveRecording() = nodes.any { it.id == "com.tencent.mobileqq:id/i40" } && recordButton() == null
    fun previewSendButton() = unique { it.id == "com.tencent.mobileqq:id/listen_panel_send_tv" && it.text == "发送" && it.clickable }
    fun hasPreview() = previewSendButton() != null
        && unique { it.id == "com.tencent.mobileqq:id/ebx" && it.description == "试听" && it.clickable } != null
    fun previewSeconds(): Int? {
        val time = unique { it.id == "com.tencent.mobileqq:id/izm" }?.text ?: return null
        val match = Regex("(\\d+):(\\d{2})").matchEntire(time) ?: return null
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    companion object {
        // QQ's bubble contains integer seconds. The encoded audio keeps its own sample duration.
        fun bubbleSeconds(durationMs: Int): Int {
            require(durationMs in 1..60000)
            return (durationMs / 1000.0).roundToInt().coerceAtLeast(1)
        }
    }
}
