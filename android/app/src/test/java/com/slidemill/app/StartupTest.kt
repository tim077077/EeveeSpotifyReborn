package com.slidemill.app

import android.content.Intent
import android.widget.TextView
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The app opens: the activity and the engine service start without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {
    @Test fun service_starts() {
        Robolectric.buildService(EngineService::class.java, Intent()).create().startCommand(0, 1)
    }

    @Test fun activity_starts() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @Test fun a_crash_is_shown_on_the_next_launch() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "last-crash.txt").writeText("java.lang.IllegalStateException: boom\n\tat com.slidemill.app.X")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val texts = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v is TextView) texts += v.text.toString()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity.window.decorView)
        assertTrue(texts.toString(), texts.any { "boom" in it })
        texts.clear()
        walk(activity.window.decorView)
        val found = ArrayList<android.view.View>().also { activity.window.decorView.findViewsWithText(it, "Open Slidemill", android.view.View.FIND_VIEWS_WITH_TEXT) }
        found.first { it is android.widget.Button }.performClick()
        assertNull(Crash.pending(app)) // cleared, and the app starts normally
    }
}
