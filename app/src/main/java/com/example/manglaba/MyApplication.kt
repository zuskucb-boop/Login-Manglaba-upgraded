package com.example.manglaba

import android.app.Application
import android.content.Intent

class MyApplication : Application() {
    override fun onTerminate() {
        super.onTerminate()
        // Stop service when app is terminated
        stopService(Intent(this, WashingMonitorService::class.java))
    }
}