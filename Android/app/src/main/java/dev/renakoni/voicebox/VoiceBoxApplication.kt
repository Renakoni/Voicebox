package dev.renakoni.voicebox

import android.app.Application
import android.content.Context
import dev.renakoni.voicebox.i18n.UiLanguageContext

class VoiceBoxApplication : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))
}
