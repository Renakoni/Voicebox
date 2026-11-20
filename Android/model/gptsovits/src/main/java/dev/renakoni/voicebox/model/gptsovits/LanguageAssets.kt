package dev.renakoni.voicebox.model.gptsovits

import android.content.Context
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi
import java.io.File

internal object LanguageAssets {
    fun directory(context: Context, name: String): File =
        File(context.getExternalFilesDir(null), "RuntimeAssets/language_frontends/$name").also {
            requireUi(it.isDirectory) { UiText("resource_missing_files", "RuntimeAssets/language_frontends/$name") }
        }
}
