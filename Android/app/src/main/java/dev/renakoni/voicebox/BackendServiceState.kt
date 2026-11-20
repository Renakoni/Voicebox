package dev.renakoni.voicebox

import dev.renakoni.voicebox.inference.ExecutionBackend
import dev.renakoni.voicebox.inference.GenerationStage

data class BackendServiceState(
    val requestId: String? = null,
    val generationEpoch: Long = 0,
    val errorCode: String? = null,
    val stage: GenerationStage = GenerationStage.IDLE,
    val message: String = "",
    val busy: Boolean = false,
    val requestedBackend: ExecutionBackend = ExecutionBackend.CPU,
    val resolvedBackend: ExecutionBackend = ExecutionBackend.CPU,
    val runtimeLabel: String = "ORT CPU EP",
    val initializedModelId: String? = null,
    val latestOutputFilePath: String? = null,
    val progressPercent: Int? = null,
    val progressLabel: String? = null,
    val generationDurationMs: Long? = null,
    val lastCompletedAtMs: Long? = null,
)
