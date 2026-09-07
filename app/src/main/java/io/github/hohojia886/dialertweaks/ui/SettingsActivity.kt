package io.github.hohojia886.dialertweaks.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.shape.ShapeAppearanceModel
import io.github.hohojia886.dialertweaks.BuildConfig
import io.github.hohojia886.dialertweaks.R
import io.github.hohojia886.dialertweaks.utils.PreferenceKeys
import io.github.hohojia886.dialertweaks.utils.IpcManager

/**
 * SettingsActivity: The main configuration interface for DialerTweaks.
 * Manages dual-preference synchronization (CE/DE storage), real-time IPC broadcasts
 * for setting updates, and UI state orchestration for Google Dialer modules.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_settings)

        // Adjust padding for edge-to-edge transparency
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.appbar).setPadding(0, systemBars.top, 0, 0)
            val content = findViewById<View>(R.id.content_container)
            content.setPadding(
                content.paddingLeft,
                content.paddingTop,
                content.paddingRight,
                systemBars.bottom + 16
            )
            insets
        }

        val deContext = createDeviceProtectedStorageContext()
        val dePrefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, MODE_PRIVATE)
        val cePrefs = getSharedPreferences(IpcManager.PREF_NAME, MODE_PRIVATE)

        setupDialerMods(dePrefs, cePrefs)
        setupDebugCard(dePrefs, cePrefs)
        displayVersion()
    }

    private fun setupDialerMods(dePrefs: SharedPreferences, cePrefs: SharedPreferences) {
        val layoutCallRecordingSection = findViewById<View>(R.id.layout_call_recording_section)
        val cardDisableAnnouncement = findViewById<View>(R.id.card_disable_announcement)
        val switchCallRecording = findViewById<MaterialSwitch>(R.id.switch_call_recording)
        val switchDisableCallNotes = findViewById<MaterialSwitch>(R.id.switch_disable_call_notes)

        if (BuildConfig.ENABLE_CALL_RECORDING) {
            layoutCallRecordingSection.visibility = View.VISIBLE
            
            // 1. Voice Announcement Mute Switch
            setupM3Switch(cePrefs, dePrefs, R.id.switch_disable_announcement, PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true)

            // 2. Call Recording Switch (Rule 1 & Rule 2)
            setupM3Switch(cePrefs, dePrefs, R.id.switch_call_recording, PreferenceKeys.ENABLE_CALL_RECORDING, true) { isChecked ->
                cardDisableAnnouncement.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked) {
                    // Rule 2: When Call Recording is turned ON, automatically turn OFF Call Notes
                    if (switchDisableCallNotes.isChecked) {
                        switchDisableCallNotes.isChecked = false
                    } else {
                        saveDoublePref(PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, false, cePrefs, dePrefs)
                        IpcManager.sendUpdateBroadcast(this, PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, false)
                    }
                }
            }
            cardDisableAnnouncement.visibility = if (dePrefs.getBoolean(PreferenceKeys.ENABLE_CALL_RECORDING, true)) View.VISIBLE else View.GONE

            // 3. Call Notes Switch (Rule 3)
            setupM3Switch(cePrefs, dePrefs, R.id.switch_disable_call_notes, PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, true) { isChecked ->
                if (isChecked) {
                    // Rule 3: When Call Notes is turned ON, automatically turn OFF Call Recording
                    if (switchCallRecording.isChecked) {
                        switchCallRecording.isChecked = false
                    } else {
                        saveDoublePref(PreferenceKeys.ENABLE_CALL_RECORDING, false, cePrefs, dePrefs)
                        IpcManager.sendUpdateBroadcast(this, PreferenceKeys.ENABLE_CALL_RECORDING, false)
                    }
                    cardDisableAnnouncement.visibility = View.GONE
                }
            }
        } else {
            layoutCallRecordingSection.visibility = View.GONE
        }
    }

    private fun setupDebugCard(dePrefs: SharedPreferences, cePrefs: SharedPreferences) {
        val groupDebug = findViewById<View>(R.id.group_debug)
        if (!BuildConfig.DEBUG) {
            groupDebug.visibility = View.GONE
            return
        }
        groupDebug.visibility = View.VISIBLE

        val masterSwitch = findViewById<MaterialSwitch>(R.id.switch_master_log)
        val masterEnabled = dePrefs.getBoolean(PreferenceKeys.ENABLE_MASTER_LOG, false)
        
        masterSwitch.isChecked = masterEnabled
        toggleDebugSubSettingsVisibility(masterEnabled)

        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveDoublePref(PreferenceKeys.ENABLE_MASTER_LOG, isChecked, cePrefs, dePrefs)
            IpcManager.sendUpdateBroadcast(this, PreferenceKeys.ENABLE_MASTER_LOG, isChecked)
            toggleDebugSubSettingsVisibility(isChecked)
        }

        setupM3Switch(cePrefs, dePrefs, R.id.switch_log_call_recording, PreferenceKeys.LOG_CALL_RECORDING, true)
        setupM3Switch(cePrefs, dePrefs, R.id.switch_log_call_notes, PreferenceKeys.LOG_CALL_NOTES, true)
    }

    private fun toggleDebugSubSettingsVisibility(visible: Boolean) {
        val ids = listOf(
            R.id.card_log_call_recording, R.id.card_log_call_notes
        )
        val visibility = if (visible) View.VISIBLE else View.GONE
        ids.forEach { findViewById<View>(it).visibility = visibility }

        val cardMasterLog = findViewById<MaterialCardView>(R.id.card_master_log)
        val shapeAppearanceRes = if (visible) R.style.ShapeAppearance_Settings_Card_Top else R.style.ShapeAppearance_Settings_Card_All
        cardMasterLog.shapeAppearanceModel = ShapeAppearanceModel.builder(this, shapeAppearanceRes, 0).build()

        val params = cardMasterLog.layoutParams as LinearLayout.LayoutParams
        params.bottomMargin = if (visible) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics).toInt() else 0
        cardMasterLog.layoutParams = params
    }

    private fun displayVersion() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.text_version).text = getString(R.string.version_display, pInfo.versionName)
        } catch (_: Exception) {}
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
