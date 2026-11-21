package dev.renakoni.voicebox.bridge

import android.os.Bundle

data class OfficialVoice(val ref: ModelRef, val name: String, val variantLabelKey: String) {
    val id get() = ref.modelId
}

data class OptionControl(val name: String, val label: String, val defaultValue: String,
    val min: Float, val max: Float, val increments: Int, val integral: Boolean = false, val unit: String = "")

data class ResourcePack(val id: String, val titleKey: String,
    val downloadBytes: Long, val remainingBytes: Long, val installed: Boolean, val variants: Set<String>)

enum class LibraryAction { DOWNLOAD_VOICE, IMPORT_ARCHIVE, IMPORT_PAIR, EDIT_REFERENCE, CLEAR_CACHE, PREVIEW_PAIR, INSTALL_RESOURCES }

/** Optional operations implemented by packages which supply downloadable or editable voices. */
interface VoiceLibrary {
    fun recoverInstallations()
    fun officialVoices(): List<OfficialVoice>
    fun variantLabel(variant: String): String
    fun resourcePacks(cancel: () -> Unit): List<ResourcePack>
    fun resourceHint(variant: String): String?
    val controls: List<OptionControl>
    fun studioOptions(values: Map<String, String>): Map<String, String>
    fun perform(action: LibraryAction, arguments: Bundle, context: GenerationContext,
        progress: (String, Int?) -> Unit): Bundle
}
