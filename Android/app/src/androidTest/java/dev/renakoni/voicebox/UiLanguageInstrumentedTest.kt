package dev.renakoni.voicebox

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.renakoni.voicebox.i18n.UiLanguageContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiLanguageInstrumentedTest {
    @Test fun onlyPrimaryChineseSelectsChinese() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cases = mapOf(
            "zh-CN,en-US" to "声音创作",
            "zh-TW,en-US" to "声音创作",
            "en-US,zh-CN" to "Create speech",
            "ja-JP,zh-CN" to "Create speech",
            "ar,zh-CN" to "Create speech",
        )
        for ((languages, expected) in cases) {
            val configuration = Configuration().apply {
                setLocales(LocaleList.forLanguageTags(languages))
                fontScale = 1.3f
            }
            val localized = UiLanguageContext(context.createConfigurationContext(configuration))
            assertEquals(languages, expected, localized.getString(R.string.create_title))
            assertEquals(1.3f, localized.resources.configuration.fontScale)
        }
    }
}
