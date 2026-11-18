package dev.rinstel.genie_tts

import android.app.Application
import android.content.Context
import dev.rinstel.genie_tts.i18n.UiLanguageContext

class VoiceboxApplication : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))
}
