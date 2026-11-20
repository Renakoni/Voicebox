package dev.renakoni.voicebox

import android.content.Context
import android.os.Bundle
import dev.renakoni.voicebox.core.BackendRegistry
import dev.renakoni.voicebox.core.BridgePackages
import dev.renakoni.voicebox.core.InstalledBackend

fun registeredBackends(context: Context, settings: AppSettingsRepository = AppSettingsRepository(context),
    verifiedGpu: (String) -> Boolean = { false }) = BackendRegistry(BridgePackages(context).packages.map { archive ->
    InstalledBackend(context, archive) {
        Bundle().apply {
            putInt("t2sThreads", settings.t2sCpuThreads)
            putInt("vocoderThreads", settings.vocoderCpuThreads)
            putBoolean("allowNpu", settings.deviceAccelerationEnabled)
            putBoolean("webGpuVerified", verifiedGpu(archive.spec.id))
        }
    }
})
