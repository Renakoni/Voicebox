package dev.renakoni.voicebox.qqvoice

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.checkUi
import dev.renakoni.voicebox.core.i18n.errorUi

import java.util.UUID

/** QQ foreground workflow. The user selects the actual chat in QQ. */
internal class QqDraftAutomation(private val adb: LocalAdb) {
    private var selectedWindow: String? = null

    private fun snapshot(): QqUiSnapshot {
        val file = "/data/local/tmp/voicebox-qq-ui-${UUID.randomUUID()}.xml"
        // Do not persist chat text. The one-shot hierarchy is deleted before the command exits.
        val xml = adb.shell("umask 077; trap 'rm -f $file' EXIT; uiautomator dump --compressed $file >/dev/null 2>&1 && cat $file")
        return QqUiSnapshot(xml)
    }
    private fun focusGuard() = """
        focus() { dumpsys window | sed -n '/mCurrentFocus=/p'; }
        current="${'$'}(focus)"
        case "${'$'}current" in *com.tencent.mobileqq/*) ;; *) exit 21;; esac
        ${selectedWindow?.let { "[ \"\$current\" = ${LocalAdb.quote(it)} ] || exit 22" } ?: ""}
    """.trimIndent()

    private fun tap(node: QqUiNode) {
        adb.shell("${focusGuard()}\ninput tap ${node.x} ${node.y}")
    }

    /** Never use whichever chat happened to be open when VoiceBox launched QQ. */
    fun chooseRecipient(session: QqDraftSession, status: (UiText) -> Unit): String {
        var ui = snapshot()
        for (attempt in 0..2) {
            checkUi(!ui.hasPreview()) { UiText(R.string.qq_existing_draft) }
            checkUi(!ui.hasActiveRecording()) { UiText(R.string.qq_recording_active) }
            if (ui.isConversationList()) break
            checkUi(ui.conversationName() != null) { UiText(R.string.qq_start_from_list) }
            // Closing the recording panel / keyboard may take a Back before leaving the chat.
            adb.shell("${focusGuard()}\ninput keyevent KEYCODE_BACK")
            ui = snapshot()
        }
        checkUi(ui.isConversationList()) { UiText(R.string.qq_list_unavailable) }
        session.beginSelection()
        status(UiText(R.string.qq_choose_recipient))
        val deadline = System.nanoTime() + 120_000_000_000L
        while (System.nanoTime() < deadline) {
            val focus = adb.shell("dumpsys window | sed -n '/mCurrentFocus=/p'")
            checkUi(focus.contains("com.tencent.mobileqq/")) { UiText(R.string.qq_selection_cancelled) }
            ui = snapshot()
            val name = ui.conversationName()
            if (name != null && (ui.voiceButton() != null || ui.recordButton() != null || ui.recordTab() != null)) {
                checkUi(!ui.hasPreview()) { UiText(R.string.qq_chat_has_draft) }
                selectedWindow = adb.shell("dumpsys window | sed -n '/mCurrentFocus=/p'")
                status(UiText(R.string.qq_preparing_recipient, name))
                return name
            }
            Thread.sleep(350)
        }
        errorUi(UiText(R.string.qq_selection_timeout))
    }

    fun prepareAndSend(session: QqDraftSession, durationMs: Int, recipient: String, status: (UiText) -> Unit) {
        val seconds = QqUiSnapshot.bubbleSeconds(durationMs)
        // At QQ's automatic recording limit the central button may already become Preview.
        // Do not automate that boundary until its controls have been verified separately.
        checkUi(seconds < 60) { UiText(R.string.qq_audio_too_long) }
        var ui = snapshot()
        ui.requireConversation(recipient)
        checkUi(!ui.hasPreview()) { UiText(R.string.qq_existing_draft) }
        checkUi(!ui.hasActiveRecording()) { UiText(R.string.qq_recording_blocks_automation) }
        session.selectRecipient(recipient)
        if (ui.recordButton() == null) {
            if (ui.recordTab() == null) {
                tap(ui.voiceButton() ?: errorUi(UiText(R.string.qq_voice_button_missing)))
                ui = snapshot()
                ui.requireConversation(recipient)
            }
            tap(ui.recordTab() ?: errorUi(UiText(R.string.qq_recording_screen_unsupported)))
            ui = snapshot()
            ui.requireConversation(recipient)
        }
        val record = ui.recordButton() ?: errorUi(UiText(R.string.qq_record_button_missing))
        session.arm(adb)
        // Both taps run on the phone, so host/ADB round trips do not determine the duration.
        // If the foreground window changes, never tap the new screen. A terminated command
        // attempts to stop only the recording it started in the same QQ window.
        adb.shell("""
            set -e
            ${focusGuard()}
            initial="${'$'}current"
            recording=0
            stop_recording() {
                [ "${'$'}recording" = 1 ] || return 0
                recording=0
                [ "${'$'}(focus)" = "${'$'}initial" ] || return 22
                input tap ${record.x} ${record.y}
            }
            trap stop_recording EXIT
            trap 'stop_recording; exit 23' HUP INT TERM
            input tap ${record.x} ${record.y}
            recording=1
            sleep $seconds.15
            stop_recording
        """.trimIndent(), timeoutSeconds = seconds + 10L)
        ui = snapshot()
        ui.requireConversation(recipient)
        checkUi(ui.hasPreview()) { UiText(R.string.qq_preview_missing) }
        checkUi(ui.previewSeconds() == seconds) { UiText(R.string.qq_duration_mismatch) }
        session.replace(adb)

        status(UiText(R.string.qq_verifying_recipient, recipient))
        ui = snapshot()
        ui.requireConversation(recipient)
        checkUi(ui.hasPreview() && ui.previewSeconds() == seconds) { UiText(R.string.qq_draft_changed) }
        session.beginSend(adb, recipient)
        tap(requireNotNull(ui.previewSendButton()))
        ui = snapshot()
        ui.requireConversation(recipient)
        checkUi(!ui.hasPreview()) { UiText(R.string.qq_send_attempted) }
        session.submitted()
    }
}
