package com.iptv.app

import android.app.Application
import android.content.Context

class IPTVApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: Application
            private set
        val context: Context get() = instance.applicationContext
    }
}
