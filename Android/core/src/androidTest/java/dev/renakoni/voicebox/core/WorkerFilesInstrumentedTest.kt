package dev.renakoni.voicebox.core

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkerFilesInstrumentedTest {
    @Test fun cleaningInterruptedRequestDoesNotFollowItsVoiceLibraryLink() {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "worker-test").toFile()
        val model = File(root, "voice").also { it.mkdir() }
        val weights = File(model, "weights").apply { writeText("voice data") }
        val scratch = File(root, "request").also { it.mkdir() }
        try {
            Files.createSymbolicLink(File(scratch, "model").toPath(), model.toPath())
            deleteWorkerFiles(scratch)
            assertFalse(scratch.exists())
            assertEquals("voice data", weights.readText())
        } finally {
            deleteWorkerFiles(root)
        }
    }
}
