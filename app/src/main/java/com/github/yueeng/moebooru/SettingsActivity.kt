@file:Suppress("unused")

package com.github.yueeng.moebooru

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.*
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    override fun enableSettingsMenu(): Boolean = false
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<SeekBarPreference>("app.cache_size")?.let { seek ->
            lifecycleScope.launchWhenCreated {
                MoeSettings.cache.asFlow().collectLatest {
                    seek.summary = (it * (1L shl 20)).sizeString()
                }
            }
        }
        findPreference<Preference>("github")?.let {
            it.setOnPreferenceClickListener {
                requireContext().openWeb(github)
                true
            }
        }
        findPreference<Preference>("update")?.let {
            it.setOnPreferenceClickListener {
                requireContext().openWeb(release)
                true
            }
        }
        findPreference<Preference>("about")?.let {
            it.summary = getString(R.string.app_version, getString(R.string.app_name), BuildConfig.VERSION_NAME)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
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

    val recreate = MutableLiveData(Unit)
    val animation = preferences.stringLiveData(KEY_ANIMATION, "default")
    val quality = preferences.booleanLiveData(KEY_HIGH_QUALITY, false)
    val safe = preferences.booleanLiveData(KEY_SAFE_MODE, false)
    val cache = preferences.intLiveData(KEY_CACHE_SIZE, 256)
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

    init {
        ProcessLifecycleOwner.get().lifecycleScope.launchWhenCreated {
            animation.asFlow().distinctUntilChanged().drop(1).collectLatest {
                recreate.postValue(Unit)
            }
        }
        ProcessLifecycleOwner.get().lifecycleScope.launchWhenCreated {
            daynight.asFlow().distinctUntilChanged().drop(1).collectLatest {
                AppCompatDelegate.setDefaultNightMode(it)
                recreate.postValue(Unit)
            }
        }
    }
}

abstract class SharedPreferenceLiveData<T>(val sharedPrefs: SharedPreferences, val key: String, private val defValue: T) : LiveData<T>() {
    init {
        value = this.getValueFromPreferences(key, defValue)
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == this.key) {
            value = getValueFromPreferences(key, defValue)
        }
    }

    abstract fun getValueFromPreferences(key: String, defValue: T): T

    override fun onActive() {
        super.onActive()
        value = getValueFromPreferences(key, defValue)
        sharedPrefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onInactive() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onInactive()
    }
}

class SharedPreferenceIntLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Int) :
    SharedPreferenceLiveData<Int>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(key: String, defValue: Int): Int = sharedPrefs.getInt(key, defValue)
}

class SharedPreferenceStringLiveData(sharedPrefs: SharedPreferences, key: String, defValue: String?) :
    SharedPreferenceLiveData<String?>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(key: String, defValue: String?): String? = sharedPrefs.getString(key, defValue)
}

class SharedPreferenceBooleanLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Boolean) :
    SharedPreferenceLiveData<Boolean>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(key: String, defValue: Boolean): Boolean = sharedPrefs.getBoolean(key, defValue)
}

class SharedPreferenceFloatLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Float) :
    SharedPreferenceLiveData<Float>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(key: String, defValue: Float): Float = sharedPrefs.getFloat(key, defValue)
}

class SharedPreferenceLongLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Long) :
    SharedPreferenceLiveData<Long>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(key: String, defValue: Long): Long = sharedPrefs.getLong(key, defValue)
}

class SharedPreferenceStringSetLiveData(sharedPrefs: SharedPreferences, key: String, defValue: Set<String>?) :
    SharedPreferenceLiveData<Set<String>?>(sharedPrefs, key, defValue) {
    override fun getValueFromPreferences(key: String, defValue: Set<String>?): Set<String>? = sharedPrefs.getStringSet(key, defValue)
}

fun SharedPreferences.intLiveData(key: String, defValue: Int): SharedPreferenceLiveData<Int> {
    return SharedPreferenceIntLiveData(this, key, defValue)
}

fun SharedPreferences.stringLiveData(key: String, defValue: String?): SharedPreferenceLiveData<String?> {
    return SharedPreferenceStringLiveData(this, key, defValue)
}

fun SharedPreferences.booleanLiveData(key: String, defValue: Boolean): SharedPreferenceLiveData<Boolean> {
    return SharedPreferenceBooleanLiveData(this, key, defValue)
}

fun SharedPreferences.floatLiveData(key: String, defValue: Float): SharedPreferenceLiveData<Float> {
    return SharedPreferenceFloatLiveData(this, key, defValue)
}

fun SharedPreferences.longLiveData(key: String, defValue: Long): SharedPreferenceLiveData<Long> {
    return SharedPreferenceLongLiveData(this, key, defValue)
}

fun SharedPreferences.stringSetLiveData(key: String, defValue: Set<String>?): SharedPreferenceLiveData<Set<String>?> {
    return SharedPreferenceStringSetLiveData(this, key, defValue)
}