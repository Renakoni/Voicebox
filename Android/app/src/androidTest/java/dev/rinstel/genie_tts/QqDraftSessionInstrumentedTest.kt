package dev.rinstel.genie_tts

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rinstel.genie_tts.qqvoice.LocalAdb
import dev.rinstel.genie_tts.qqvoice.QqDraftSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QqDraftSessionInstrumentedTest {
    private fun isolated(work: (ContextWrapper, File) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "qq-session-test").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = directory
        }
        try { work(context, directory) }
        finally {
            QqDraftSession(context).finish()
            File(directory, "qq-draft").delete()
            File(directory, "qq-adb-identity.p12").delete()
            directory.delete()
        }
    }

    @Test fun choosingMustPrecedeRecipientBindingAndSurvivesRecreation() = isolated { context, _ ->
        val session = QqDraftSession(context)
        session.prepare(File(context.getExternalFilesDir("QqVoiceTests"), "fixture.wav"))
        assertThrows(IllegalStateException::class.java) { session.selectRecipient("another person") }
        session.beginSelection()
        val recreated = QqDraftSession(context)
        assertEquals("choosing", recreated.status()!!.getString("phase"))
        recreated.selectRecipient("another person")
        assertEquals("another person", session.status()!!.getString("recipient"))
        assertEquals("prepared", session.status()!!.getString("phase"))
        recreated.beginSelection()
        assertFalse(session.status()!!.has("recipient"))
        assertThrows(IllegalArgumentException::class.java) { recreated.selectRecipient(" ") }
    }

    @Test fun uncertainSendCannotBeRetriedAfterRecreation() = isolated { context, directory ->
        QqDraftSession(context)
        val record = File(directory, "qq-draft/request.json")
        record.writeText(JSONObject().put("phase", "replaced").put("recipient", "chosen person").toString())
        val offlineAdb = LocalAdb(context)
        try {
            // Both failures must occur before any ADB connection or file access is attempted.
            assertThrows(IllegalStateException::class.java) {
                QqDraftSession(context).beginSend(offlineAdb, "different person")
            }
            record.writeText(JSONObject().put("phase", "sending").put("recipient", "chosen person").toString())
            assertThrows(IllegalStateException::class.java) {
                QqDraftSession(context).beginSend(offlineAdb, "chosen person")
            }
            assertThrows(IllegalStateException::class.java) { QqDraftSession(context).beginSelection() }
        } finally { offlineAdb.disconnect() }
    }
}
