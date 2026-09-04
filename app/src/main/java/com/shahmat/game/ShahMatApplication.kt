package com.shahmat.game

import android.app.Application
import android.util.Log

class ShahMatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e("ShahMatI", "Uncaught exception", throwable)
        }
    }
}