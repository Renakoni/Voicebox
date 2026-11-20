package dev.renakoni.voicebox.core

import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.core.i18n.UiText

data class OfficialDownloadState(val voice: ModelRef? = null, val label: UiText? = null, val percent: Int? = null,
    val running: Boolean = false, val completed: Boolean = false, val error: UiText? = null, val packageId: String? = null)
