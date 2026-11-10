package com.roywatson.android_litert_object_recog

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise actual Activity startup, including merged content-provider initialization. */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {
    @Test fun mainActivityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
