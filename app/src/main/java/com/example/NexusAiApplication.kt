package com.example

import android.app.Application
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer

class NexusAiApplication : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        if (!::container.isInitialized) {
            container = DefaultAppContainer(this)
        }
    }
}
