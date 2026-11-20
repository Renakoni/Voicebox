package dev.renakoni.voicebox

import dev.renakoni.voicebox.model.gptsovits.OfficialModelCatalog
import dev.renakoni.voicebox.model.gptsovits.OfficialModelInstaller

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** Explicit network regression; leaves the user's installed models untouched. */
@RunWith(AndroidJUnit4::class)
class OfficialModelInstallInstrumentedTest {
    @Test fun downloadsAndConvertsFromAnEmptyDirectory() {
        val modelId = InstrumentationRegistry.getArguments().getString("officialModel")
        assumeNotNull(modelId)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = OfficialModelCatalog(context)
        val model = catalog.models.single { it.id == modelId }
        val root = Files.createTempDirectory(context.getExternalFilesDir(null)!!.toPath(), ".official-test-").toFile()
        val start = System.nanoTime()
        try {
            var lastLabel = ""
            OfficialModelInstaller(root, catalog, context.assets::open).install(model, {}) { state ->
                val label = state.label?.resolve(context.resources).orEmpty()
                if (label != lastLabel || state.percent == 100) {
                    Log.i("OfficialInstallTest", "$label ${state.percent ?: ""}")
                    lastLabel = label
                }
            }
            assertTrue(catalog.installed(root, model))
            assertFalse(root.resolve(".official-download").exists())
            assertFalse(root.resolve(".official-vocoder").exists())
            assertFalse(root.walkTopDown().any { it.extension in setOf("ckpt", "pth") })
            Log.i("OfficialInstallTest", "PASS $modelId: ${(System.nanoTime() - start) / 1_000_000} ms")
        } finally { root.deleteRecursively() }
    }
}
