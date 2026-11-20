package dev.renakoni.voicebox.core.i18n

import android.content.res.Resources
import androidx.annotation.StringRes
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Resolve at the UI boundary so background work does not choose the display language. */
class UiText private constructor(@StringRes val resourceId: Int, val key: String?, val arguments: Array<out Any>) {
    constructor(@StringRes resourceId: Int, vararg arguments: Any) : this(resourceId = resourceId, key = null, arguments = arguments)
    constructor(key: String, vararg arguments: Any) : this(resourceId = 0, key = key, arguments = arguments)

    companion object {
        fun literal(text: String) = UiText(dev.renakoni.voicebox.core.R.string.voicebox_literal_message, text)
    }

    fun resolve(resources: Resources): String {
        val id = key?.let {
            val application = resources.getResourcePackageName(dev.renakoni.voicebox.core.R.string.voicebox_message_package)
            resources.getIdentifier(it, "string", application)
        } ?: resourceId
        return resources.getString(id, *arguments)
    }
}

interface LocalizedFailure {
    val text: UiText
}

@PublishedApi
internal class LocalizedArgumentException(override val text: UiText) :
    IllegalArgumentException("Invalid input (message ${text.key ?: text.resourceId})"), LocalizedFailure

@PublishedApi
internal class LocalizedStateException(override val text: UiText) :
    IllegalStateException("Operation failed (message ${text.key ?: text.resourceId})"), LocalizedFailure

@OptIn(ExperimentalContracts::class)
inline fun requireUi(value: Boolean, message: () -> UiText) {
    contract { returns() implies value }
    if (!value) throw LocalizedArgumentException(message())
}

@OptIn(ExperimentalContracts::class)
inline fun checkUi(value: Boolean, message: () -> UiText) {
    contract { returns() implies value }
    if (!value) throw LocalizedStateException(message())
}

fun errorUi(message: UiText): Nothing = throw LocalizedStateException(message)

fun Throwable.userText(): UiText? =
    generateSequence(this) { it.cause }.filterIsInstance<LocalizedFailure>()
        .firstOrNull()?.text

fun Throwable.userMessage(resources: Resources): String? = userText()?.resolve(resources)
