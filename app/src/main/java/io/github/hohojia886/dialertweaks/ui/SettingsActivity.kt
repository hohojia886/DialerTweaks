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

        // Dialer Mods
        val layoutCallRecordingSub = findViewById<View>(R.id.layout_call_recording_sub)
        val switchDisableAnnouncement = findViewById<MaterialSwitch>(R.id.switch_disable_announcement)

        // 1. Setup Standard Disable Voice Announcement (Sub of Call Recording)
        setupM3Switch(cePrefs, dePrefs, R.id.switch_disable_announcement, PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true)

        // 2. Setup Call Recording Main Switch (Links to Disable Voice Announcement)
        setupM3Switch(cePrefs, dePrefs, R.id.switch_call_recording, PreferenceKeys.ENABLE_CALL_RECORDING, true) { isChecked ->
            layoutCallRecordingSub.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                // Linkage: If main toggle is off, sub-bypass must be off too
                if (switchDisableAnnouncement.isChecked) {
                    switchDisableAnnouncement.isChecked = false
                } else {
                    saveDoublePref(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, false, cePrefs, dePrefs)
                    IpcManager.sendUpdateBroadcast(this, PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, false)
                }
            }
        }
        layoutCallRecordingSub.visibility = if (dePrefs.getBoolean(PreferenceKeys.ENABLE_CALL_RECORDING, true)) View.VISIBLE else View.GONE

        // 3. Setup Call Notes Announcement (Independent)
        setupM3Switch(cePrefs, dePrefs, R.id.switch_disable_call_notes_announcement, PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, true)

        // Debug Card
        setupDebugCard(dePrefs, cePrefs)
        
        displayVersion()
    }

    private fun setupDebugCard(dePrefs: SharedPreferences, cePrefs: SharedPreferences) {
        val subLayout = findViewById<View>(R.id.layout_debug_sub_settings)
        val masterSwitch = findViewById<MaterialSwitch>(R.id.switch_master_log)
        val masterEnabled = dePrefs.getBoolean(PreferenceKeys.ENABLE_MASTER_LOG, false)
        
        masterSwitch.isChecked = masterEnabled
        subLayout.visibility = if (masterEnabled) View.VISIBLE else View.GONE

        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveDoublePref(PreferenceKeys.ENABLE_MASTER_LOG, isChecked, cePrefs, dePrefs)
            IpcManager.sendUpdateBroadcast(this, PreferenceKeys.ENABLE_MASTER_LOG, isChecked)
            subLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        setupM3Switch(cePrefs, dePrefs, R.id.switch_log_call_recording, PreferenceKeys.LOG_CALL_RECORDING, true)
    }

    private fun saveDoublePref(key: String, value: Any, ce: SharedPreferences, de: SharedPreferences) {
        val ceEdit = ce.edit()
        val deEdit = de.edit()
        when (value) {
            is Boolean -> { ceEdit.putBoolean(key, value); deEdit.putBoolean(key, value) }
            is Int -> { ceEdit.putInt(key, value); deEdit.putInt(key, value) }
            is Float -> { ceEdit.putFloat(key, value); deEdit.putFloat(key, value) }
            is Long -> { ceEdit.putLong(key, value); deEdit.putLong(key, value) }
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

    private fun setupM3Switch(ce: SharedPreferences, de: SharedPreferences, id: Int, key: String, default: Boolean, onToggle: ((Boolean) -> Unit)? = null) {
        val view = findViewById<MaterialSwitch>(id)
        view.isChecked = de.getBoolean(key, default)
        view.setOnCheckedChangeListener { _, isChecked ->
            saveDoublePref(key, isChecked, ce, de)
            onToggle?.invoke(isChecked)
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
