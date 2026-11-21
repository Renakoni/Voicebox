package dev.renakoni.voicebox

import android.util.Log
import dev.renakoni.voicebox.core.DeviceFacts

internal object DeviceNpu {
    @JvmStatic private external fun hasAndroidAccelerator(): Boolean

    private val androidAccelerator by lazy {
        runCatching {
            System.loadLibrary("voicebox_device_npu")
            hasAndroidAccelerator()
        }.onFailure { Log.w("VoiceBoxAcceleration", "Android accelerator discovery failed", it) }.getOrDefault(false)
    }

    // The SM8650 Hexagon is reachable through QNN even on ROMs whose NNAPI
    // device list contains only the reference CPU.
    fun isSupported(device: DeviceFacts) =
        device.soc.equals("SM8650", ignoreCase = true) || androidAccelerator
}
