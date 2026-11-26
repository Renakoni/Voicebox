package dev.renakoni.voicebox

import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.core.BackendRegistry
import dev.renakoni.voicebox.core.BridgePackages
import dev.renakoni.voicebox.core.DeviceFacts
import dev.renakoni.voicebox.core.InstalledBackend
import dev.renakoni.voicebox.core.i18n.UiLanguageContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class VoiceBoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceFacts.initialize(this)
        if (Application.getProcessName() == packageName) {
            CoroutineScope(Dispatchers.IO).launch { DeviceFacts.hasVulkanCompute }
        }
    }

    val bridgePackages by lazy { BridgePackages(this) }
    internal val mingModelPackage by lazy { MingModelPackage(this) }
    internal val qwenModelPackage by lazy { QwenModelPackage(this) }
    val backends by lazy {
        val settings = AppSettingsRepository(this)
        BackendRegistry(bridgePackages.packages.map { archive ->
            val externalNpuRuntime = if (archive.spec.id == "gptsovits") File(filesDir, "QualcommRuntime") else null
            InstalledBackend(this, archive, externalNpuRuntime) {
                Bundle().apply {
                    putInt("t2sThreads", settings.t2sCpuThreads)
                    putInt("vocoderThreads", settings.vocoderCpuThreads)
                    putBoolean("allowNpu", settings.deviceAccelerationEnabled)
                }
            }
        })
    }

    internal fun installedVoiceModels(providers: List<InstalledBackend>, mingWeightsInstalled: Boolean? = null,
        qwenWeightsInstalled: Boolean? = null): List<CharacterModel> =
        providers.flatMap { provider ->
            if (provider.id == MingModelPackage.BACKEND) {
                if (mingWeightsInstalled ?: mingModelPackage.officialPack().installed) provider.discoverCharacterModels()
                else emptyList()
            } else if (provider.id == QwenModelPackage.BACKEND) {
                val officialInstalled = qwenWeightsInstalled ?: qwenModelPackage.pack(QwenModelPackage.TALKER) {}.installed
                if (officialInstalled) provider.discoverCharacterModels() else {
                    val official = provider.officialVoices().singleOrNull()?.ref ?: return@flatMap emptyList()
                    provider.discoverCharacterModels().filterNot { it.ref == official }
                }
            } else provider.discoverCharacterModels().filter { provider.inspect(it).isComplete }
        }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))
}
