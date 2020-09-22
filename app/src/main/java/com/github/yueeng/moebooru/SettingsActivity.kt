package com.github.yueeng.moebooru

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import kotlinx.coroutines.flow.collectLatest

class SettingsActivity : MoeActivity(R.layout.fragment_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? SettingsFragment ?: SettingsFragment()
        supportFragmentManager.beginTransaction().replace(R.id.preferences, fragment).commit()
    }
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
    }
}

object MoeSettings {
    private val context: Context get() = MainApplication.instance()
    private val config by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private const val KEY_DAY_NIGHT_MODE = "app.day_night_mode"
    private const val KEY_SAFE_MODE = "app.safe_mode"
    private const val KEY_CACHE_SIZE = "app.cache_size"
    private const val KEY_HIGH_QUALITY = "app.high_quality"
    private fun daynightvalues() = when (val value = config.getString(KEY_DAY_NIGHT_MODE, null)?.toIntOrNull()) {
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
        AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY -> value
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    val quality = MutableLiveData(config.getBoolean(KEY_HIGH_QUALITY, false))
    val safe = MutableLiveData(config.getBoolean(KEY_SAFE_MODE, true))
    val daynight = MutableLiveData(daynightvalues())
    val cache = MutableLiveData(config.getInt(KEY_CACHE_SIZE, 256))

    init {
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_SAFE_MODE -> safe.postValue(config.getBoolean(key, true))
                KEY_DAY_NIGHT_MODE -> {
                    AppCompatDelegate.setDefaultNightMode(daynightvalues())
                    daynight.postValue(daynightvalues())
                }
                KEY_CACHE_SIZE -> cache.postValue(config.getInt(KEY_CACHE_SIZE, 256))
            }
        }
    }
}