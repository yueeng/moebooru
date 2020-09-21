package com.github.yueeng.moebooru

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference

class MainApplication : Application() {
    companion object {
        private var app: WeakReference<MainApplication>? = null

        fun instance() = app!!.get()!!
    }

    init {
        app = WeakReference(this)
    }

    lateinit var okHttp: OkHttpClient
    override fun onCreate() {
        AppCompatDelegate.setDefaultNightMode(MoeSettings.daynight.value!!)
        super.onCreate()

        MoeSettings.cache.observe(ProcessLifecycleOwner.get(), {
            okHttp = createOkHttpClient()
        })
    }
}
