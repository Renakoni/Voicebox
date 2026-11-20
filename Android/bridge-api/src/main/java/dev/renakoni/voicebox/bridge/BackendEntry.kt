package dev.renakoni.voicebox.bridge

import android.content.Context
import android.os.Bundle
import java.io.File

/** Shared by the parent class loader. A backend package contains only its own implementation. */
interface BackendEntry {
    fun open(context: Context, directory: File, work: File, settings: Bundle): BackendProvider
    companion object { const val CONTRACT = 1 }
}
