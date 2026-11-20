package dev.renakoni.voicebox

import dev.renakoni.voicebox.core.BackendRegistry
import dev.renakoni.voicebox.model.gptsovits.DeviceAcceleration
import dev.renakoni.voicebox.model.gptsovits.GptProvider

import android.content.Context
import dev.renakoni.voicebox.model.ming.MingProvider

/** Static composition root until PR4 sources providers from verified packages. */
fun registeredBackends(context: Context, settings: AppSettingsRepository = AppSettingsRepository(context),
    acceleration: DeviceAcceleration = DeviceAcceleration(context)) = BackendRegistry(listOf(
    GptProvider(context, acceleration, { settings.t2sCpuThreads }, { settings.vocoderCpuThreads },
        { settings.deviceAccelerationEnabled }),
    MingProvider(context),
))
