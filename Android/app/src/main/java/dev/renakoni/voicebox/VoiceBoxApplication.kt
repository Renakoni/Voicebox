package dev.renakoni.voicebox

import android.app.Application
import android.content.Context
import dev.renakoni.voicebox.core.i18n.UiLanguageContext

class VoiceBoxApplication : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))
}
