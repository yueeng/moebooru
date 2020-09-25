package com.github.yueeng.moebooru

import android.app.ActivityOptions
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.transition.Explode
import android.transition.Fade
import android.transition.Slide
import android.transition.TransitionSet
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.platform.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
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

open class MoeActivity(contentLayoutId: Int) : AppCompatActivity(contentLayoutId) {
    override fun startActivity(intent: Intent?, options: Bundle?) =
        super.startActivity(
            intent, when (MoeSettings.animation.value) {
                "shared" -> options
                "scale", "explode", "fade", "slide", "axis_x", "axis_y", "axis_z" -> ActivityOptions.makeSceneTransitionAnimation(this).toBundle()
                else -> null
            }
        )

    private fun ensureTransform() {
        when (MoeSettings.animation.value) {
            "shared" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                findViewById<View>(android.R.id.content).transitionName = "shared_element_container"
                setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
                setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
                window.sharedElementEnterTransition = MaterialContainerTransform().apply {
                    addTarget(android.R.id.content)
                    pathMotion = MaterialArcMotion()
                    duration = 400L
                    isElevationShadowEnabled = true
                }
                window.sharedElementReturnTransition = MaterialContainerTransform().apply {
                    addTarget(android.R.id.content)
                    pathMotion = MaterialArcMotion()
                    duration = 300L
                    isElevationShadowEnabled = true
                }
                window.allowEnterTransitionOverlap = true
            }
            "scale" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val growingUp = MaterialElevationScale(true)
                val growingDown = MaterialElevationScale(false)
                window.enterTransition = growingUp
                window.exitTransition = growingDown
                window.reenterTransition = growingUp
                window.returnTransition = growingDown
            }
            "fade" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val fade = MaterialFadeThrough()
                window.enterTransition = fade
                window.exitTransition = fade
                window.reenterTransition = fade
                window.returnTransition = fade
            }
            "axis_x" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val forward = MaterialSharedAxis(MaterialSharedAxis.X, true)
                val backward = MaterialSharedAxis(MaterialSharedAxis.X, false)
                window.reenterTransition = backward
                window.exitTransition = forward
                window.enterTransition = forward
                window.returnTransition = backward
            }
            "axis_y" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val forward = MaterialSharedAxis(MaterialSharedAxis.Y, true)
                val backward = MaterialSharedAxis(MaterialSharedAxis.Y, false)
                window.reenterTransition = backward
                window.exitTransition = forward
                window.enterTransition = forward
                window.returnTransition = backward
            }
            "axis_z" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val forward = MaterialSharedAxis(MaterialSharedAxis.Z, true)
                val backward = MaterialSharedAxis(MaterialSharedAxis.Z, false)
                window.reenterTransition = backward
                window.exitTransition = forward
                window.enterTransition = forward
                window.returnTransition = backward
            }
            "explode" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val explodeFade = TransitionSet().addTransition(Explode()).addTransition(Fade())
                window.enterTransition = explodeFade
                window.exitTransition = explodeFade
                window.returnTransition = explodeFade
                window.reenterTransition = explodeFade
            }
            "slide" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val start = TransitionSet().addTransition(Slide(Gravity.START)).addTransition(Fade())
                val end = TransitionSet().addTransition(Slide(Gravity.END)).addTransition(Fade())
                window.enterTransition = end
                window.exitTransition = start
                window.returnTransition = end
                window.reenterTransition = start
            }
            else -> Unit
        }
//        window.allowEnterTransitionOverlap = false
//        window.allowReturnTransitionOverlap = false
    }

    @OptIn(FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        ensureTransform()
        super.onCreate(savedInstanceState)
        lifecycleScope.launchWhenCreated {
            val flow1 = MoeSettings.daynight.asFlow().drop(1)
            val flow2 = MoeSettings.animation.asFlow().drop(1)
            flowOf(flow1, flow2).flattenMerge(2).collectLatest {
                recreate()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.app, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.settings -> true.also {
            val options = ActivityOptions.makeSceneTransitionAnimation(this, findViewById(item.itemId), "shared_element_container")
            startActivity(Intent(this, SettingsActivity::class.java), options.toBundle())
        }
        else -> super.onOptionsItemSelected(item)
    }
}
