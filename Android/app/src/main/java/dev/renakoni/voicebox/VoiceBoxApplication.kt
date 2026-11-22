package dev.renakoni.voicebox

import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.core.BackendRegistry
import dev.renakoni.voicebox.core.BridgePackages
import dev.renakoni.voicebox.core.InstalledBackend
import dev.renakoni.voicebox.core.i18n.UiLanguageContext

class VoiceBoxApplication : Application() {
    val bridgePackages by lazy { BridgePackages(this) }
    internal val mingModelPackage by lazy { MingModelPackage(this) }
    val backends by lazy {
        val settings = AppSettingsRepository(this)
        BackendRegistry(bridgePackages.packages.map { archive ->
            InstalledBackend(this, archive) {
                Bundle().apply {
                    putInt("t2sThreads", settings.t2sCpuThreads)
                    putInt("vocoderThreads", settings.vocoderCpuThreads)
                    putBoolean("allowNpu", settings.deviceAccelerationEnabled)
                }
            }
        })
    }

    internal fun completeModels(providers: List<InstalledBackend>, mingWeightsInstalled: Boolean? = null): List<CharacterModel> =
        providers.flatMap { provider ->
            if (provider.id == MingModelPackage.BACKEND) {
                if (mingWeightsInstalled ?: mingModelPackage.officialPack().installed) provider.discoverCharacterModels()
                else emptyList()
            } else provider.discoverCharacterModels().filter { provider.inspect(it).isComplete }
        }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))
}
