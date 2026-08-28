package io.github.hohojia886.dialertweaks.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.hohojia886.dialertweaks.R
import io.github.hohojia886.dialertweaks.utils.PreferenceKeys
import io.github.hohojia886.dialertweaks.utils.IpcManager

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        val deContext = createDeviceProtectedStorageContext()
        val dePrefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, MODE_PRIVATE)
        val cePrefs = getSharedPreferences(IpcManager.PREF_NAME, MODE_PRIVATE)

        setupM3Switch(cePrefs, dePrefs, R.id.switch_call_recording, PreferenceKeys.ENABLE_CALL_RECORDING, true)
        setupM3Switch(cePrefs, dePrefs, R.id.switch_disable_announcement, PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true)
        setupM3Switch(cePrefs, dePrefs, R.id.switch_master_log, PreferenceKeys.ENABLE_MASTER_LOG, true)

        displayVersion()
    }

    private fun saveDoublePref(key: String, value: Any, ce: SharedPreferences, de: SharedPreferences) {
        val ceEdit = ce.edit()
        val deEdit = de.edit()
        when (value) {
            is Boolean -> { ceEdit.putBoolean(key, value); deEdit.putBoolean(key, value) }
            is Int -> { ceEdit.putInt(key, value); deEdit.putInt(key, value) }
        }
        ceEdit.apply()
        deEdit.apply()
    }

    private fun displayVersion() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.text_version).text = getString(R.string.version_display, pInfo.versionName)
        } catch (_: Exception) {}
    }

    private fun setupM3Switch(ce: SharedPreferences, de: SharedPreferences, id: Int, key: String, default: Boolean) {
        val view = findViewById<MaterialSwitch>(id)
        view.isChecked = de.getBoolean(key, default)
        view.setOnCheckedChangeListener { _, isChecked ->
            saveDoublePref(key, isChecked, ce, de)
            IpcManager.sendUpdateBroadcast(this, key, isChecked)
        }
    }

    override fun onPause() {
        super.onPause()
        val deContext = createDeviceProtectedStorageContext()
        val dePrefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, MODE_PRIVATE)
        IpcManager.syncAllSettings(this, dePrefs)
    }
}
