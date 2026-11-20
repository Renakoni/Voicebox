package dev.renakoni.voicebox.bridge

import android.os.Bundle

data class OfficialVoice(val ref: ModelRef, val name: String, val variantLabelKey: String) {
    val id get() = ref.modelId
}

data class OptionControl(val name: String, val label: String, val defaultValue: String,
    val min: Float, val max: Float, val increments: Int, val integral: Boolean = false, val unit: String = "")

enum class LibraryAction { DOWNLOAD_VOICE, IMPORT_ARCHIVE, IMPORT_PAIR, EDIT_REFERENCE, CLEAR_CACHE, PREVIEW_PAIR }

/** Optional operations implemented by packages which supply downloadable or editable voices. */
interface VoiceLibrary {
    fun officialVoices(): List<OfficialVoice>
    fun variantLabel(variant: String): String
    fun resourcesReady(variant: String): Boolean
    fun resourceHint(variant: String): String?
    val controls: List<OptionControl>
    fun studioOptions(values: Map<String, String>): Map<String, String>
    fun perform(action: LibraryAction, arguments: Bundle, context: GenerationContext,
        progress: (String, Int?) -> Unit): Bundle
}
