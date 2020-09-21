package com.github.yueeng.moebooru

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
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
        okHttp = createOkHttpClient()
        ProcessLifecycleOwner.get().lifecycleScope.launchWhenCreated {
            MoeSettings.cache.asFlow().drop(1).collect {
                okHttp = createOkHttpClient()
            }
        }
    }
}
