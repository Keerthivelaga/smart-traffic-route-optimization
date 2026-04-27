package com.smarttraffic.app

import android.app.Application
import com.smarttraffic.core_engine.security.TamperGuard
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TrafficApplication : Application() {

    @Inject
    lateinit var tamperGuard: TamperGuard

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG && !tamperGuard.verifySignature()) {
            throw SecurityException("Application signature validation failed")
        }
    }
}

