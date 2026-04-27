package com.smarttraffic.app

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPerformanceTest {
    @Test
    fun coldStart_under1500ms() {
        val start = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { }
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        val isEmulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
        val thresholdMs = if (isEmulator) 3000L else 1500L
        assertTrue("Launch exceeded ${thresholdMs}ms: $elapsed", elapsed < thresholdMs)
    }
}
