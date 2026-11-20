package dev.renakoni.voicebox.model.gptsovits


import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageFrontendInstrumentedTest {
    @Test fun matchesUpstreamJapaneseEnglishAndUnknownWordOracles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("language-frontends.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val japanese = JapaneseTextFeatureExtractor.fromAssets(context)
        EnglishTextFeatureExtractor.fromAssets(context).use { english ->
            listOf("japanese" to japanese, "english" to english).forEach { (language, frontend) ->
                val cases = json.getJSONArray(language)
                repeat(cases.length()) { i ->
                    val case = cases.getJSONObject(i)
                    val actual = frontend.extract(language, case.getString("text"))
                    val expected = case.getJSONArray("phones").let { array -> List(array.length()) { array.getString(it) } }
                    assertEquals(case.getString("text"), expected, actual.symbols)
                    assertArrayEquals(case.getJSONArray("phone_ids").let { array -> LongArray(array.length()) { array.getLong(it) } },
                        actual.phoneIds.values)
                    assertTrue(actual.bert.values.all { it == 0f })
                    assertEquals(actual.symbols.size.toLong(), actual.bert.shape[0])
                }
            }
        }
        EnglishPronunciationModel(LanguageAssets.directory(context, "english")).use { model ->
            val cases = json.getJSONArray("english_unknown")
            repeat(cases.length()) { i ->
                val case = cases.getJSONObject(i)
                val expected = case.getJSONArray("phones").let { array -> List(array.length()) { array.getString(it) } }
                assertEquals(case.getString("text"), expected, model.predict(case.getString("text")))
            }
        }
    }
}
