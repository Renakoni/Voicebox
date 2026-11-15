package dev.rinstel.genie_tts

import android.net.Uri
import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.rinstel.genie_tts.inference.ModelAssetRepository
import dev.rinstel.genie_tts.inference.OptimizedDecoderAssets
import dev.rinstel.genie_tts.inference.SplitVocoderAssets
import dev.rinstel.genie_tts.inference.OrtSessionRuntime
import dev.rinstel.genie_tts.inference.OrtRuntimeFeatureExtractor
import dev.rinstel.genie_tts.inference.RuntimeAssetRepository
import dev.rinstel.genie_tts.inference.GenerationRequest
import dev.rinstel.genie_tts.inference.GenerationStage
import dev.rinstel.genie_tts.inference.BackendRuntimeCallbacks
import dev.rinstel.genie_tts.checkpoint.CheckpointImporter
import dev.rinstel.genie_tts.studio.StudioViewModel
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
        val reference = case?.let { CheckpointImporter.Reference(File(source, it.getString("reference_file")),
            it.getString("reference_text"), it.optString("reference_language", "zh")) }
        assertTrue("Push the official test pair first", gpt.isFile && sovits.isFile)
        val start = System.nanoTime()
        val connected = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val service = AtomicReference<GenieBackendService>()
        val imported = AtomicReference<Result<CheckpointImporter.Result>>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.set((binder as GenieBackendService.LocalBinder).getService()); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = GenieBackendService.serviceIntent(context)
        context.startForegroundService(intent)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Service connection timeout", connected.await(30, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post {
                val accepted = service.get().importCheckpointPair(case?.getString("name") ?: "官方 V2ProPlus", Uri.fromFile(gpt), Uri.fromFile(sovits),
                    onProgress = { android.util.Log.i("CheckpointImportTest", it) },
                    onComplete = { imported.set(it); finished.countDown() }, reference = reference)
                if (!accepted) { imported.set(Result.failure(IllegalStateException("Import refused"))); finished.countDown() }
            }
            assertTrue("Import timeout", finished.await(180, TimeUnit.SECONDS))
        } finally { context.unbindService(connection) }
        val result = imported.get().getOrThrow()
        verifyImportedModel(context, result, source, start)
        if (reference != null) {
            val repository = ModelAssetRepository(context)
            val runtimeAssets = RuntimeAssetRepository(context)
            val model = repository.discoverCharacterModels().single { it.id == result.characterId }
            assertEquals(case.getString("architecture"), result.architecture)
            val runtime = OrtSessionRuntime(repository, runtimeAssets) { backend ->
                OrtRuntimeFeatureExtractor(context, runtimeAssets, backend)
            }
            try {
                val generated = runtime.generate(GenerationRequest(model, "zh", synthesisText = case.getString("text"),
                    referenceAudioPath = reference.audioFile.absolutePath, referenceText = reference.text,
                    promptLanguage = reference.language),
                    object : BackendRuntimeCallbacks {
                        override fun onStage(stage: GenerationStage, message: String, progressPercent: Int?, progressLabel: String?) {
                            android.util.Log.i("CheckpointImportTest", "$stage: $message")
                        }
                    })
                assertTrue(generated.audioSamples > 32000)
                val reportFile = File(source, "android-import-result.json")
                reportFile.writeText(JSONObject(reportFile.readText()).put("architecture", result.architecture)
                    .put("synthesis_performed", true).put("audio_samples", generated.audioSamples)
                    .put("output", generated.file.absolutePath).toString(2))
            } finally { runtime.close() }
        }
    }

    @Test fun importsCommunityVoiceThroughStudioAndKeepsItReadyAfterRecreation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = File(app.getExternalFilesDir(null), "checkpoint-community-source")
        val case = JSONObject(File(source, "import-case.json").readText())
        val gpt = File(source, case.getString("gpt_file"))
        val sovits = File(source, case.getString("sovits_file"))
        val reference = File(source, case.getString("reference_file"))
        val transcript = case.getString("reference_text")
        val name = case.getString("name")
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
                vm.importCheckpoints()
                assertEquals("请选择配套的参考音频，并填写音频中的原文。", vm.checkpointError)
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
                vm.importCheckpoints()
            }
            awaitStudio { !vm.importing && !vm.loading }
            lateinit var result: CheckpointImporter.Result
            var selectedId = ""
            onMain {
                assertNull(vm.checkpointError)
                assertNull(vm.checkpointDraft)
                assertNull(vm.draft)
                assertEquals(0, vm.tab)
                val voice = requireNotNull(vm.selectedVoice)
                assertEquals(name, voice.name)
                assertEquals(transcript, voice.transcript)
                assertArrayEquals(reference.readBytes(), File(voice.audioPath).readBytes())
                selectedId = voice.id
                result = CheckpointImporter.Result(vm.modelFor(voice)!!.id, voice.name)
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

    private fun verifyImportedModel(context: Context, result: CheckpointImporter.Result, source: File, start: Long) {
        val repository = ModelAssetRepository(context)
        val model = repository.discoverCharacterModels().single { it.id == result.characterId }
        assertEquals(result.displayName, model.displayName)
        val modelDirectory = repository.installToPrivateStorage(model.modelFiles)
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
        assertTrue(OptimizedDecoderAssets.resolve(modelDirectory, true).values.any { it.name == "mobile-decoder-shared.onnx" })
        SplitVocoderAssets.validate(modelDirectory)
        val graphs = listOf("t2s_encoder_fp32.onnx", "mobile-prefill.onnx", "mobile-decoder-shared.onnx",
            "prompt_encoder_fp32.onnx", "split-encoder.onnx", "split-hifigan.onnx")
        for (graph in graphs) {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                OrtEnvironment.getEnvironment().createSession(File(modelDirectory, graph).absolutePath, options).use { session ->
                    assertTrue(session.inputNames.isNotEmpty()); assertTrue(session.outputNames.isNotEmpty())
                }
            }
        }
        report.put("weight_hashes", hashes).put("loaded_graphs", org.json.JSONArray(graphs))
            .put("status", "passed").put("synthesis_performed", false)
        File(source, "android-import-result.json").writeText(report.toString(2))
        // Keep the imported model for the user, but never retain checkpoint source copies.
        assertFalse(modelDirectory.parentFile!!.walkTopDown().any { it.extension in setOf("ckpt", "pth") })
        assertFalse(context.getExternalFilesDir(null)!!.listFiles()!!.any { it.name.startsWith(".genie-checkpoint-") })
        assertTrue(ModelAssetRepository(context).discoverCharacterModels().any { it.id == model.id && it.displayName == result.displayName })
    }
}
