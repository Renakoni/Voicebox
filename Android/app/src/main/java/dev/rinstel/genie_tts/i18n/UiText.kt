package dev.rinstel.genie_tts.i18n

import android.content.res.Resources
import androidx.annotation.StringRes
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Resolve at the UI boundary so background work does not choose the display language. */
class UiText(@StringRes val resourceId: Int, vararg val arguments: Any) {
    fun resolve(resources: Resources): String = resources.getString(resourceId, *arguments)
}

interface LocalizedFailure {
    val text: UiText
}

@PublishedApi
internal class LocalizedArgumentException(override val text: UiText) :
    IllegalArgumentException("Invalid input (message ${text.resourceId})"), LocalizedFailure

@PublishedApi
internal class LocalizedStateException(override val text: UiText) :
    IllegalStateException("Operation failed (message ${text.resourceId})"), LocalizedFailure

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
