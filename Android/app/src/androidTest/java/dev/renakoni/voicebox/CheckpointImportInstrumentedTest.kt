package dev.renakoni.voicebox

import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.bridge.VoiceLibrary
import dev.renakoni.voicebox.bridge.ExecutionBackend
import android.net.Uri
import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.bridge.BackendRuntimeCallbacks
import dev.renakoni.voicebox.studio.StudioViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real checkpoint import; an optional case supplies reference audio for synthesis. */
@RunWith(AndroidJUnit4::class)
class CheckpointImportInstrumentedTest {
    @Test fun importsOfficialPairAndLoadsOptimizedGraphs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourcePath = InstrumentationRegistry.getArguments().getString("checkpointSource", "checkpoint-test-source")!!
        val source = if (sourcePath.startsWith("/")) File(sourcePath) else File(context.getExternalFilesDir(null), sourcePath)
        val case = File(source, "import-case.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        val gpt = File(source, "s1v3.ckpt")
        val sovits = File(source, case?.getString("sovits_file") ?: "s2Gv2ProPlus.pth")
        val reference = case?.let { ReferenceExample(File(source, it.getString("reference_file")),
            it.getString("reference_text"), it.optString("reference_language", "zh")) }
        val runtimeFixture = File(source, "RuntimeAssets")
        if (runtimeFixture.isDirectory) {
            runtimeFixture.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = File(context.getExternalFilesDir(null), "RuntimeAssets/${file.relativeTo(runtimeFixture).invariantSeparatorsPath}")
                target.parentFile!!.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
        assertTrue("Push the official test pair first", gpt.isFile && sovits.isFile)
        val start = System.nanoTime()
        val connected = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val service = AtomicReference<VoiceBoxBackendService>()
        val imported = AtomicReference<Result<ImportedVoice>>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.set((binder as VoiceBoxBackendService.LocalBinder).getService()); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = VoiceBoxBackendService.serviceIntent(context)
        context.startForegroundService(intent)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Service connection timeout", connected.await(30, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post {
                val accepted = service.get().importCheckpointPair(case?.getString("name") ?: "官方 V2ProPlus", Uri.fromFile(gpt), Uri.fromFile(sovits),
                    onProgress = { android.util.Log.i("CheckpointImportTest", it.resolve(context.resources)) },
                    onComplete = { imported.set(it); finished.countDown() }, reference = reference,
                    replaceCharacterId = InstrumentationRegistry.getArguments().getString("replaceCharacterId"))
                if (!accepted) { imported.set(Result.failure(IllegalStateException("Import refused"))); finished.countDown() }
            }
            assertTrue("Import timeout", finished.await(180, TimeUnit.SECONDS))
        } finally { context.unbindService(connection) }
        val result = imported.get().getOrThrow()
        verifyImportedModel(context, result, source, start)
        if (reference != null) {
            val repository = registeredBackends(context)
            val model = repository.discoverCharacterModels().single { it.ref == result.ref }
            assertEquals(case.getString("architecture"), result.ref.variant)
            val runtime = repository.provider(model).createRuntime(ExecutionBackend.CPU)
            try {
                val generated = runtime.generate(GenerationRequest(model, "zh", synthesisText = case.getString("text"),
                    referenceAudioPath = reference.audioFile.absolutePath, referenceText = reference.referenceText,
                    promptLanguage = reference.language),
                    object : BackendRuntimeCallbacks {
                        override fun onStage(stage: GenerationStage, message: String, progressPercent: Int?, progressLabel: String?) {
                            android.util.Log.i("CheckpointImportTest", "$stage: $message")
                        }
                    })
                assertTrue(generated.audioSamples > 32000)
                val reportFile = File(source, "android-import-result.json")
                reportFile.writeText(JSONObject(reportFile.readText()).put("architecture", result.ref.variant)
                    .put("synthesis_performed", true).put("audio_samples", generated.audioSamples)
                    .put("sample_rate", generated.sampleRate).put("output", generated.file.absolutePath).toString(2))
            } finally { runtime.close() }
        }
    }

    @Test fun importsCommunityVoiceThroughStudioAndKeepsItReadyAfterRecreation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = InstrumentationRegistry.getArguments().getString("checkpointSource")?.let(::File)
            ?: File(app.getExternalFilesDir(null), "checkpoint-community-source")
        val case = JSONObject(File(source, "import-case.json").readText())
        val gpt = File(source, case.getString("gpt_file"))
        val sovits = File(source, case.getString("sovits_file"))
        val reference = File(source, case.getString("reference_file"))
        val transcript = case.getString("reference_text")
        val name = case.getString("name")
        val architecture = case.optString("architecture", "v2ProPlus")
        val language = case.optString("reference_language", "auto")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var owner = ViewModelStore()
        lateinit var vm: StudioViewModel
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun createStudio() = onMain {
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[StudioViewModel::class.java]
            vm.warmupEnabled = false
        }
        fun awaitStudio(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                onMain { ready = condition() }
                if (ready) return
                SystemClock.sleep(100)
            }
            fail("Studio operation timed out")
        }
        createStudio()
        try {
            awaitStudio { !vm.loading && vm.connected }
            // Cancelling an audio selection must remove the App's temporary sample.
            onMain { vm.beginCheckpointImport(); vm.pickReference(Uri.fromFile(reference)) }
            awaitStudio { !vm.fileBusy }
            var cancelledSample = ""
            onMain {
                assertNull(vm.checkpointError)
                cancelledSample = vm.checkpointDraft!!.audioPath
                vm.closeCheckpointImport()
            }
            assertFalse(File(cancelledSample).exists())

            onMain { vm.beginCheckpointImport(); vm.editCheckpointName(name); vm.pickCheckpoint(Uri.fromFile(gpt), true) }
            awaitStudio { !vm.fileBusy }
            onMain { vm.pickCheckpoint(Uri.fromFile(sovits), false) }
            awaitStudio { !vm.fileBusy }
            onMain {
                assertEquals(dev.renakoni.voicebox.core.i18n.UiText((registeredBackends(app).provider("gptsovits") as VoiceLibrary).variantLabel(architecture)).resolve(app.resources), vm.checkpointRecognition?.resolve(app.resources))
                vm.importCheckpoints()
                assertEquals(R.string.checkpoint_reference_required, vm.checkpointError?.resourceId)
                assertFalse(vm.importing)
                vm.pickReference(Uri.fromFile(reference))
            }
            awaitStudio { !vm.fileBusy }
            var importedSample = ""
            val start = System.nanoTime()
            onMain {
                assertNull(vm.checkpointError)
                assertTrue(vm.checkpointDraft!!.durationMs in 500..30_000)
                importedSample = vm.checkpointDraft!!.audioPath
                vm.editCheckpointTranscript(transcript)
                vm.editCheckpointLanguage(language)
                vm.importCheckpoints()
            }
            awaitStudio { !vm.importing && !vm.loading }
            lateinit var result: ImportedVoice
            var selectedId = ""
            onMain {
                assertNull(vm.checkpointError)
                assertNull(vm.checkpointDraft)
                assertNull(vm.draft)
                assertEquals(0, vm.tab)
                val voice = requireNotNull(vm.selectedVoice)
                assertEquals(name, voice.name)
                assertEquals(transcript, voice.transcript)
                assertEquals(language, voice.referenceLanguage)
                assertEquals(architecture, voice.model.variant)
                assertArrayEquals(reference.readBytes(), File(voice.audioPath).readBytes())
                selectedId = voice.id
                result = ImportedVoice(vm.modelFor(voice)!!.ref, voice.name)
                vm.openVoicePicker(); vm.chooseVoice(voice)
                assertFalse(vm.choosingVoice)
                assertNull(vm.draft)
            }
            assertFalse(File(importedSample).exists())
            assertTrue(gpt.isFile && sovits.isFile && reference.isFile)
            onMain { owner.clear(); owner = ViewModelStore() }
            createStudio()
            awaitStudio { !vm.loading && vm.connected }
            onMain {
                assertEquals(selectedId, vm.selectedId)
                assertEquals(transcript, vm.selectedVoice!!.transcript)
                assertEquals(language, vm.selectedVoice!!.referenceLanguage)
                assertNotNull(vm.modelFor(vm.selectedVoice!!))
            }
            verifyImportedModel(app, result, source, start)
            val reportFile = File(source, "android-import-result.json")
            val report = JSONObject(reportFile.readText()).put("reference_text", transcript)
                .put("studio_import_passed", true).put("voice_survived_recreation", true)
                .put("temporary_reference_removed", true)
            reportFile.writeText(report.toString(2))
        } finally { onMain { owner.clear() } }
    }

    private fun verifyImportedModel(context: Context, result: ImportedVoice, source: File, start: Long) {
        val repository = registeredBackends(context)
        val model = repository.discoverCharacterModels().single { it.id == result.ref.modelId }
        assertEquals(result.displayName, model.displayName)
        assertTrue(repository.inspect(model).isComplete)
        val modelDirectory = File(context.getExternalFilesDir(null), "CharacterModels/${model.relativeModelDirectory}")
        val expected = JSONObject(File(source, "expected-import.json").readText()).getJSONObject("outputs")
        val report = JSONObject().put("character_id", model.id)
            .put("import_elapsed_ms", (System.nanoTime() - start) / 1_000_000)
        val hashes = JSONObject()
        for (name in expected.keys()) {
            val file = File(modelDirectory, name)
            assertEquals(expected.getJSONObject(name).getLong("bytes"), file.length())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(name, expected.getJSONObject(name).getString("sha256"), hash)
            hashes.put(name, hash)
        }
        report.put("weight_hashes", hashes)
            .put("status", "passed").put("synthesis_performed", false)
        File(source, "android-import-result.json").writeText(report.toString(2))
        // Keep the imported model for the user, but never retain checkpoint source copies.
        assertFalse(modelDirectory.parentFile!!.walkTopDown().any { it.extension in setOf("ckpt", "pth") })
        assertFalse(context.getExternalFilesDir(null)!!.listFiles()!!.any { it.name.startsWith(".voicebox-checkpoint-") })
        assertTrue(registeredBackends(context).discoverCharacterModels().any { it.id == model.id && it.displayName == result.displayName })
    }
}
