package dev.rinstel.genie_tts.qqvoice

import org.junit.Assert.*
import org.junit.Test

class QqUiSnapshotTest {
    private fun node(id: String = "", text: String = "", description: String = "", clickable: Boolean = true,
        app: String = "com.tencent.mobileqq", enabled: Boolean = true, bounds: String = "[10,20][110,120]") =
        """<node package="$app" resource-id="$id" text="$text" content-desc="$description"
            clickable="$clickable" enabled="$enabled" bounds="$bounds"/>"""
    private fun screen(vararg nodes: String) = QqUiSnapshot("<hierarchy>${nodes.joinToString("")}</hierarchy>")
    private fun title(name: String) = node("com.tencent.mobileqq:id/3o4", text = name)

    @Test fun onlyTheConversationTitleIdentifiesTheSelectedRecipient() {
        assertEquals("sec", screen(title("sec")).conversationName())
        screen(title("another person")).requireConversation("another person")
        val otherChat = screen(title("another person"), node(text = "sec"), node(description = "sec"))
        assertEquals("another person", otherChat.conversationName())
        assertThrows(IllegalStateException::class.java) { otherChat.requireConversation("sec") }
        assertNull(screen(node("com.tencent.mobileqq:id/3o4", text = "sec", app = "other.app")).conversationName())
        assertNull(screen(title(" ")).conversationName())
    }
    @Test fun exactVoiceControlDoesNotPlayAnExistingMessage() {
        val ui = screen(node(description = "语音9秒"), node(description = "语音", bounds = "[0,100][100,200]"))
        assertEquals(150, ui.voiceButton()?.y)
        assertNull(screen(node(description = "语音9秒")).voiceButton())
    }
    @Test fun choosingStartsFromTheListNotAnAlreadyOpenChat() {
        val row = node("com.tencent.mobileqq:id/aua", description = "sec, ,[图片],下午3:36")
        assertTrue(screen(row).isConversationList())
        assertFalse(screen(title("sec"), row).isConversationList())
        assertFalse(screen(node(text = "消息")).isConversationList())
        // Duplicate names are not looked up: the user opens the actual row in QQ.
        assertTrue(screen(row, row).isConversationList())
    }
    @Test fun ambiguousConversationTitlesAreRejected() {
        assertThrows(IllegalStateException::class.java) { screen(title("sec"), title("other")).conversationName() }
    }
    @Test fun previewRequiresTheRecordingPanelNotTheChatSendButton() {
        assertFalse(screen(node("com.tencent.mobileqq:id/send_btn", text = "发送")).hasPreview())
        assertNull(screen(node("com.tencent.mobileqq:id/send_btn", text = "发送")).previewSendButton())
        val ui = screen(node("com.tencent.mobileqq:id/listen_panel_send_tv", text = "发送"),
            node("com.tencent.mobileqq:id/ebx", description = "试听"), node("com.tencent.mobileqq:id/izm", text = "0:10"))
        assertTrue(ui.hasPreview())
        assertEquals(10, ui.previewSeconds())
    }
    @Test fun hiddenOrDisabledControlsCannotBeTapped() {
        assertNull(screen(node(description = "语音", enabled = false)).voiceButton())
        assertNull(screen(node(description = "语音", bounds = "[0,0][0,0]")).voiceButton())
        assertNull(screen(node(description = "语音", clickable = false)).voiceButton())
    }
    @Test fun activeRecordingMustNotBeDismissedToChooseSomeoneElse() {
        assertTrue(screen(node("com.tencent.mobileqq:id/i40", description = "停止录音")).hasActiveRecording())
        assertFalse(screen(node("com.tencent.mobileqq:id/i40", description = "开始录音")).hasActiveRecording())
        assertFalse(screen(title("sec")).hasActiveRecording())
    }
    @Test fun durationOnlyControlsIntegerBubbleMetadata() {
        assertEquals(10, QqUiSnapshot.bubbleSeconds(10200))
        assertEquals(9, QqUiSnapshot.bubbleSeconds(8760))
        assertEquals(1, QqUiSnapshot.bubbleSeconds(20))
        assertEquals(60, QqUiSnapshot.bubbleSeconds(60000))
        assertThrows(IllegalArgumentException::class.java) { QqUiSnapshot.bubbleSeconds(0) }
    }
    @Test fun externalEntitiesAreRejectedBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            QqUiSnapshot("""<!DOCTYPE hierarchy [<!ENTITY x SYSTEM "file:///data/local/tmp/secret">]><hierarchy/>""")
        }
    }
}
