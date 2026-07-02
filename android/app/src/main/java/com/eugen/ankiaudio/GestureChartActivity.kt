package com.eugen.ankiaudio

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-brightness, standalone view of the [GestureChart], reachable from the
 * home screen so the gestures can be looked up before a session (rather than
 * only from inside the dim study surface via a three-finger tap). A tap
 * anywhere or the back button closes it.
 */
class GestureChartActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(GestureChart.build(this) { finish() })
    }
}
