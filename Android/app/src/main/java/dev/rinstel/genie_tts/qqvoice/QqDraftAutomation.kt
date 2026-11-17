package dev.rinstel.genie_tts.qqvoice

import java.util.UUID

/** QQ foreground workflow. The user selects the actual chat in QQ. */
internal class QqDraftAutomation(private val adb: LocalAdb) {
    private var selectedWindow: String? = null

    private fun snapshot(): QqUiSnapshot {
        val file = "/data/local/tmp/genie-qq-ui-${UUID.randomUUID()}.xml"
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

    /** Never use whichever chat happened to be open when Voicebox launched QQ. */
    fun chooseRecipient(session: QqDraftSession, status: (String) -> Unit): String {
        var ui = snapshot()
        for (attempt in 0..2) {
            check(!ui.hasPreview()) { "QQ 已有未发送的草稿，请先发送或取消，再重新开始。" }
            check(!ui.hasActiveRecording()) { "QQ 正在录音，请先停止并处理该草稿。" }
            if (ui.isConversationList()) break
            check(ui.conversationName() != null) { "请先回到 QQ 消息列表，再从 Voicebox 开始。" }
            // Closing the recording panel / keyboard may take a Back before leaving the chat.
            adb.shell("${focusGuard()}\ninput keyevent KEYCODE_BACK")
            ui = snapshot()
        }
        check(ui.isConversationList()) { "未能打开 QQ 消息列表，请返回消息列表后重试。" }
        session.beginSelection()
        status("请在 QQ 选择联系人。打开会话后，将自动准备并发送这条语音。")
        val deadline = System.nanoTime() + 120_000_000_000L
        while (System.nanoTime() < deadline) {
            val focus = adb.shell("dumpsys window | sed -n '/mCurrentFocus=/p'")
            check(focus.contains("com.tencent.mobileqq/")) { "已退出 QQ 选人，没有发送语音。返回后可重新选择。" }
            ui = snapshot()
            val name = ui.conversationName()
            if (name != null && (ui.voiceButton() != null || ui.recordButton() != null || ui.recordTab() != null)) {
                check(!ui.hasPreview()) { "所选会话已有语音草稿，未开始录制。请先处理该草稿。" }
                selectedWindow = adb.shell("dumpsys window | sed -n '/mCurrentFocus=/p'")
                status("正在准备发给 $name 的语音，请保持 QQ 前台，暂勿操作。")
                return name
            }
            Thread.sleep(350)
        }
        error("选择联系人已超时，没有发送语音。请返回 Voicebox 重试。")
    }

    fun prepareAndSend(session: QqDraftSession, durationMs: Int, recipient: String, status: (String) -> Unit) {
        val seconds = QqUiSnapshot.bubbleSeconds(durationMs)
        // At QQ's automatic recording limit the central button may already become Preview.
        // Do not automate that boundary until its controls have been verified separately.
        check(seconds < 60) { "这段语音过长，暂不支持 QQ 自动发送。" }
        var ui = snapshot()
        ui.requireConversation(recipient)
        check(!ui.hasPreview()) { "QQ 已有未发送的草稿，请先发送或取消，再重新开始。" }
        check(!ui.hasActiveRecording()) { "QQ 正在录音，未开始自动操作。请先处理该录音。" }
        session.selectRecipient(recipient)
        if (ui.recordButton() == null) {
            if (ui.recordTab() == null) {
                tap(ui.voiceButton() ?: error("找不到 QQ 语音入口，请先关闭键盘或弹窗。"))
                ui = snapshot()
                ui.requireConversation(recipient)
            }
            tap(ui.recordTab() ?: error("当前 QQ 录音页面不兼容。"))
            ui = snapshot()
            ui.requireConversation(recipient)
        }
        val record = ui.recordButton() ?: error("没有找到点击录音按钮，已停止。")
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
        check(ui.hasPreview()) { "QQ 尚未进入试听页，未替换音频。请回 QQ 检查。" }
        check(ui.previewSeconds() == seconds) { "占位录音时长不符，未替换。请在 QQ 取消草稿后重试。" }
        session.replace(adb)

        status("正在核对发给 $recipient 的语音…")
        ui = snapshot()
        ui.requireConversation(recipient)
        check(ui.hasPreview() && ui.previewSeconds() == seconds) { "QQ 草稿已变化，未点击发送。" }
        session.beginSend(adb, recipient)
        tap(requireNotNull(ui.previewSendButton()))
        ui = snapshot()
        ui.requireConversation(recipient)
        check(!ui.hasPreview()) { "已尝试点击发送，请在 QQ 检查结果；程序不会自动重发。" }
        session.submitted()
    }
}
