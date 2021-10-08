package com.github.yueeng.moebooru

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.transition.Explode
import android.transition.Fade
import android.transition.Slide
import android.transition.TransitionSet
import android.view.*
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.content.res.use
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commitNow
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

class MainApplication : Application(), Thread.UncaughtExceptionHandler {
    companion object {
        private lateinit var app: MainApplication
        fun instance() = app
    }

    init {
        app = this
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(MoeSettings.daynight.value ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        startActivity(
            Intent(this, CrashActivity::class.java)
                .putExtra("e", e)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        exitProcess(1)
    }
}

class CrashActivity : AppCompatActivity(R.layout.activity_crash) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        val e = intent.getSerializableExtra("e") as Throwable
        val seq = generateSequence(e) { it.cause }
        val ex = StringWriter().use { stream ->
            PrintWriter(stream).use { writer ->
                writer.println("=====BuildConfig=====")
                BuildConfig::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) }.forEach {
                    writer.println("${it.name}: ${it.get(null)}")
                }
                writer.println()
                writer.println("=====Exception=====")
                seq.forEach {
                    it.printStackTrace(writer)
                }
                writer.println("-------------------")
            }
            stream.toString()
        }
        val span = SpannableStringBuilder.valueOf(ex)
        """^\s*at\s+${Regex.escape(javaClass.`package`!!.name)}.*$""".toRegex(RegexOption.MULTILINE).findAll(ex).forEach {
            span.setSpan(ForegroundColorSpan(Color.BLUE), it.range.first, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            """\((.*?:\d+)\)""".toRegex().find(it.value)?.groups?.get(1)?.let { sub ->
                span.setSpan(ForegroundColorSpan(Color.RED), it.range.first + sub.range.first, it.range.first + sub.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        findViewById<TextView>(R.id.text1).text = span
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(Menu.NONE, 0x1000, Menu.NONE, "COPY")?.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        0x1000 -> true.also {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("ERROR", findViewById<TextView>(R.id.text1).text))
        }
        else -> super.onOptionsItemSelected(item)
    }
}

interface IOnBackPressed {
    fun onBackPressed() = false
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
                val background = theme.obtainStyledAttributes(intArrayOf(R.attr.colorSurface)).use {
                    it.getColor(0, Color.WHITE)
                }
                window.sharedElementEnterTransition = MaterialContainerTransform().apply {
                    addTarget(android.R.id.content)
                    pathMotion = MaterialArcMotion()
                    duration = 400L
                    isElevationShadowEnabled = true
                    endContainerColor = background
                }
                window.sharedElementReturnTransition = MaterialContainerTransform().apply {
                    addTarget(android.R.id.content)
                    pathMotion = MaterialArcMotion()
                    duration = 300L
                    isElevationShadowEnabled = true
                    startContainerColor = background
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
            MoeSettings.recreate.asFlow().drop(1).collectLatest {
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
            val options = findViewById<View>(item.itemId)?.let { ActivityOptions.makeSceneTransitionAnimation(this, it, "shared_element_container") }
            startActivity(Intent(this, SettingsActivity::class.java), options?.toBundle())
        }
        R.id.search -> true.also {
            val query = (supportFragmentManager.findFragmentById(R.id.container) as? SavedFragment.Queryable)?.query() ?: Q()
            val options = findViewById<View>(item.itemId)?.let { ActivityOptions.makeSceneTransitionAnimation(this, it, "shared_element_container") }
            startActivity(Intent(this, QueryActivity::class.java).putExtra("query", query), options?.toBundle())
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        val drawer = findViewById<DrawerLayout>(R.id.drawer)
        if (drawer?.isDrawerOpen(GravityCompat.START) == true || drawer?.isDrawerOpen(GravityCompat.END) == true) {
            drawer.closeDrawers()
            return
        }
        if (supportFragmentManager.fragments.mapNotNull { it as? IOnBackPressed }.firstOrNull()?.onBackPressed() == true) {
            return
        }
        super.onBackPressed()
    }
}

class MoePermissionFragment : Fragment() {
    private lateinit var callback: (Map<String, Boolean>) -> Unit
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        callback.invoke(permissions)
    }

    fun request(vararg permissions: String, call: (Map<String, Boolean>) -> Unit) {
        callback = call
        requestPermissions.launch(permissions)
    }

}

@Suppress("MemberVisibilityCanBePrivate", "unused")
class MoePermission(val fragmentManager: FragmentManager) {
    fun request(vararg permissions: String, call: (Map<String, Boolean>) -> Unit) {
        val fragment = MoePermissionFragment()
        fragmentManager.commitNow { add(fragment, MoePermissionFragment::class.java.simpleName) }
        fragment.request(*permissions) {
            fragmentManager.commitNow { remove(fragment) }
            call(it)
        }
    }

    companion object {
        fun with(fragment: Fragment) = MoePermission(fragment.childFragmentManager)
        fun with(activity: FragmentActivity) = MoePermission(activity.supportFragmentManager)
        fun Context.isPermissionGranted(vararg permission: String): Boolean = permission.all {
            PermissionChecker.checkSelfPermission(this, it) == PermissionChecker.PERMISSION_GRANTED
        }

        fun Activity.showRequestPermissionRationale(permission: String) =
            !isPermissionGranted(permission) && ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

        fun MoePermission.checkPermissions(activity: Activity, vararg permission: String, granted: () -> Unit) = activity.run {
            if (isPermissionGranted(*permission)) {
                granted()
                return@run
            }
            request(*permission) { permissions ->
                if (permissions.all { it.value }) granted()
                else {
                    val message = permissions.filter { !it.value }
                        .map { packageManager.getPermissionInfo(it.key, PackageManager.GET_META_DATA) }
                        .mapNotNull { it.loadDescription(packageManager) }
                        .joinToString(",")
                    activity.snack(message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.app_go) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }.show()
                }
            }
        }

        fun FragmentActivity.checkPermissions(vararg permission: String, granted: () -> Unit) =
            with(this).checkPermissions(this, *permission, granted = granted)

        fun Fragment.checkPermissions(vararg permission: String, granted: () -> Unit) =
            with(this).checkPermissions(requireActivity(), *permission, granted = granted)
    }
}