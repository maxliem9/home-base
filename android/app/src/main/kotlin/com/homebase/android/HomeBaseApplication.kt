package com.homebase.android

import android.app.Application
import com.homebase.android.di.AppContainer

class HomeBaseApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
