@file:Suppress("unused")

package com.github.yueeng.moebooru

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.asFlow
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop


class SettingsActivity : MoeActivity(R.layout.fragment_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? SettingsFragment ?: SettingsFragment()
        supportFragmentManager.beginTransaction().replace(R.id.preferences, fragment).commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = false
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<SeekBarPreference>("app.cache_size")?.let { seek ->
            launchWhenCreated {
                MoeSettings.cache.asFlow().collectLatest {
                    seek.summary = (it * (1L shl 20)).sizeString()
                }
            }
        }
        val address = findPreference<EditTextPreference>("app.host_ip_address")
        launchWhenCreated {
            MoeSettings.host.asFlow().collectLatest {
                address?.isVisible = it
            }
        }
        address?.setOnBindEditTextListener {
            it.findParent<TextInputLayout>()?.hint = getString(R.string.settings_host_ip_address_default, getString(R.string.app_ip))
        }
        findPreference<Preference>("github")?.let {
            it.setOnPreferenceClickListener {
                requireContext().openWeb(github)
                true
            }
        }
        findPreference<Preference>("update")?.let {
            it.setOnPreferenceClickListener {
                (requireActivity() as? AppCompatActivity)?.checkAppUpdate()
                true
            }
        }
        findPreference<Preference>("about")?.let {
            it.summary = getString(R.string.app_version, getString(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.BUILD_TIME)
            it.setOnPreferenceClickListener {
                requireContext().startActivity(Intent(requireContext(), CrashActivity::class.java))
                true
            }
        }
    }
}

object MoeSettings {
    private val context: Context get() = MainApplication.instance()
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private const val KEY_DAY_NIGHT_MODE = "app.day_night_mode"
    private const val KEY_SAFE_MODE = "app.safe_mode"
    private const val KEY_CACHE_SIZE = "app.cache_size"
    private const val KEY_HIGH_QUALITY = "app.high_quality"
    private const val KEY_ANIMATION = "app.animation"
    private const val KEY_LIST_COLUMN = "app.list_column"
    private const val KEY_LIST_INFO = "app.list_info"
    private const val KEY_LIST_PAGE = "app.list_page"
    private const val KEY_LIST_QUALITY = "app.list_quality"
    private const val KEY_PREVIEW_COLOR = "app.preview_color"
    private const val KEY_HOST_IP = "app.host_ip"
    private const val KEY_HOST_IP_ADDRESS = "app.host_ip_address"
    private const val KEY_API_KEY = "app.api_key"
    private const val KEY_CHECK_NOTIFICATION = "app.check_notification"

    val recreate = MutableLiveData(Unit)
    val animation = preferences.stringLiveData(KEY_ANIMATION, "default")
    val quality = preferences.booleanLiveData(KEY_HIGH_QUALITY, false)
    val safe = preferences.booleanLiveData(KEY_SAFE_MODE, false)
    val cache = preferences.intLiveData(KEY_CACHE_SIZE, 256)
    val host = preferences.booleanLiveData(KEY_HOST_IP, false)
    val ip = preferences.stringLiveData(KEY_HOST_IP_ADDRESS, context.getString(R.string.app_ip))
    private val daynight_ = preferences.stringLiveData(KEY_DAY_NIGHT_MODE, "${AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM}")
    val daynight = MediatorLiveData<Int>().apply {
        addSource(daynight_) {
            value = when (val v = it?.toIntOrNull()) {
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_YES,
                AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY -> v

                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }
    }
    val column = preferences.intLiveData(KEY_LIST_COLUMN, context.resources.getInteger(R.integer.list_image_column))
    fun column() {
        preferences.edit {
            val max = context.resources.getInteger(R.integer.list_image_column) + 1
            putInt(KEY_LIST_COLUMN, if (column.value!! >= max) 1 else (column.value!! + 1))
        }
    }

    val info = preferences.booleanLiveData(KEY_LIST_INFO, false)
    val page = preferences.booleanLiveData(KEY_LIST_PAGE, false)
    val preview = preferences.booleanLiveData(KEY_LIST_QUALITY, false)
    val previewColor = preferences.booleanLiveData(KEY_PREVIEW_COLOR, false)
    private val apiKey = preferences.stringLiveData(KEY_API_KEY, null)
    suspend fun apiKey(): String? = if (apiKey.value?.isNotEmpty() == true) apiKey.value!! else Service.apiKey()?.also {
        preferences.edit { putString(KEY_API_KEY, it) }
    }

    val checkNotification = preferences.booleanLiveData(KEY_CHECK_NOTIFICATION, true)

    init {
        ProcessLifecycleOwner.get().launchWhenCreated {
            animation.asFlow().distinctUntilChanged().drop(1).collectLatest {
                recreate.postValue(Unit)
            }
        }
        ProcessLifecycleOwner.get().launchWhenCreated {
            daynight.asFlow().distinctUntilChanged().drop(1).collectLatest {
                AppCompatDelegate.setDefaultNightMode(it)
                recreate.postValue(Unit)
            }
        }
    }
}

abstract class SharedPreferenceLiveData<T>(protected val sharedPrefs: SharedPreferences, val key: String, private val defValue: T, private val always: Boolean = true) : LiveData<T>() {
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != this.key) return@OnSharedPreferenceChangeListener
        val data = getValueFromPreferences(defValue)
        if (value != data) value = data
    }

    init {
        value = this.getValueFromPreferences(defValue)
        if (always) sharedPrefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    abstract fun getValueFromPreferences(defValue: T): T
    abstract fun setValueToPreferences(value: T)
    override fun onActive() {
        super.onActive()
        if (always) return
        value = getValueFromPreferences(defValue)
        sharedPrefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onInactive() {
        super.onInactive()
        if (always) return
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }
}

class SharedPreferenceIntLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Int) :
    SharedPreferenceLiveData<Int>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(defValue: Int): Int = sharedPrefs.getInt(key, defValue)
    override fun setValueToPreferences(value: Int) = sharedPrefs.edit { putInt(key, value) }
}

class SharedPreferenceStringLiveData(sharedPrefs: SharedPreferences, key: String, defValue: String?) :
    SharedPreferenceLiveData<String?>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(defValue: String?): String? = sharedPrefs.getString(key, defValue)
    override fun setValueToPreferences(value: String?) = sharedPrefs.edit { putString(key, value) }
}

class SharedPreferenceBooleanLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Boolean) :
    SharedPreferenceLiveData<Boolean>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(defValue: Boolean): Boolean = sharedPrefs.getBoolean(key, defValue)
    override fun setValueToPreferences(value: Boolean) = sharedPrefs.edit { putBoolean(key, value) }
}

class SharedPreferenceFloatLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Float) :
    SharedPreferenceLiveData<Float>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(defValue: Float): Float = sharedPrefs.getFloat(key, defValue)
    override fun setValueToPreferences(value: Float) = sharedPrefs.edit { putFloat(key, value) }
}

class SharedPreferenceLongLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Long) :
    SharedPreferenceLiveData<Long>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(defValue: Long): Long = sharedPrefs.getLong(key, defValue)
    override fun setValueToPreferences(value: Long) = sharedPrefs.edit { putLong(key, value) }
}

class SharedPreferenceStringSetLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Set<String>?) :
    SharedPreferenceLiveData<Set<String>?>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(defValue: Set<String>?): Set<String>? = sharedPrefs.getStringSet(key, defValue)
    override fun setValueToPreferences(value: Set<String>?) = sharedPrefs.edit { putStringSet(key, value) }
}

fun SharedPreferences.intLiveData(key: String, defValue: Int): SharedPreferenceLiveData<Int> =
    SharedPreferenceIntLiveData(this, key, defValue)

fun SharedPreferences.stringLiveData(key: String, defValue: String?): SharedPreferenceLiveData<String?> =
    SharedPreferenceStringLiveData(this, key, defValue)

fun SharedPreferences.booleanLiveData(key: String, defValue: Boolean): SharedPreferenceLiveData<Boolean> =
    SharedPreferenceBooleanLiveData(this, key, defValue)

fun SharedPreferences.floatLiveData(key: String, defValue: Float): SharedPreferenceLiveData<Float> =
    SharedPreferenceFloatLiveData(this, key, defValue)

fun SharedPreferences.longLiveData(key: String, defValue: Long): SharedPreferenceLiveData<Long> =
    SharedPreferenceLongLiveData(this, key, defValue)

fun SharedPreferences.stringSetLiveData(key: String, defValue: Set<String>?): SharedPreferenceLiveData<Set<String>?> =
    SharedPreferenceStringSetLiveData(this, key, defValue)