package dev.renakoni.voicebox.bridge

enum class GenerationStage(
    val progressPercent: Int,
) {
    IDLE(0),
    INSPECTING_RESOURCES(0),
    INSTALLING_MODEL(5),
    INITIALIZING_BACKEND(15),
    PREPARING_FEATURES(20),
    RUNNING_INFERENCE(46),
    WRITING_OUTPUT(98),
    COMPLETED(100),
    ERROR(0),
    CANCELLED(0),
}
