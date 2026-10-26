package com.slidemill.app

import android.content.Intent
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The app opens: the activity and the engine service start and run their clock without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 35])
class StartupTest {
    @Test fun app_opens_and_runs() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
        Robolectric.buildService(EngineService::class.java, Intent()).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        val e = Slidemill.engine(org.robolectric.RuntimeEnvironment.getApplication())
        e.tick()
        e.relay.status()
        Thread.sleep(500)
    }
}
