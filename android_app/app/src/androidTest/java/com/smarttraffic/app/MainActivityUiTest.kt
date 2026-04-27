package com.smarttraffic.app

import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertNotNull
import org.junit.Test

class MainActivityUiTest {
    @Test
    fun mainActivity_launchesWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }
}

