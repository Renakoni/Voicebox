package dev.renakoni.voicebox.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Package data is resolved from the verified installation, independently of host resources. */
class BackendAssetContext(base: Context, internal val assetsRoot: File) : ContextWrapper(base)

fun Context.openBackendAsset(name: String) =
    if (this is BackendAssetContext) File(assetsRoot, name).inputStream() else assets.open(name)
