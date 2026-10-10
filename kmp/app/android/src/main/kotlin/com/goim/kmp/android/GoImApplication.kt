package com.goim.kmp.android

import android.app.Application
import com.goim.kmp.di.initKoin

class GoImApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(this)
    }
}
