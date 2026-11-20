package dev.renakoni.voicebox

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/** Keep device tests foreground without binding the service under test. */
class BenchmarkHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
