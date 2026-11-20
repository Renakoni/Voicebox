package dev.renakoni.voicebox.core.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Use only the primary system language; secondary locales must not select Chinese. */
class UiLanguageContext(base: Context) : ContextWrapper(base) {
    private var sourceConfiguration: Configuration? = null
    private lateinit var localizedResources: Resources

    @Synchronized override fun getResources(): Resources {
        val current = baseContext.resources.configuration
        if (current != sourceConfiguration) {
            val localized = Configuration(current).apply {
                setLocale(if (current.locales[0].language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH)
            }
            localizedResources = baseContext.createConfigurationContext(localized).resources
            sourceConfiguration = Configuration(current)
        }
        return localizedResources
    }
}
