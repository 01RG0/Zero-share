package com.ultrashare

import android.app.Application
import timber.log.Timber

class UltraShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
