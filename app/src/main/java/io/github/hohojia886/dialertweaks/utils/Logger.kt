package io.github.hohojia886.dialertweaks.utils

import android.content.Intent
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * Logger: Standardized logging utility for the DialerTweaks module.
 * Features centralized toggles for each functional area, auto-prefixing with "DLTK_",
 * and a Master switch that controls all output across processes.
 */
object Logger {

    interface Logger {
        fun v(tag: String, msg: String)
        fun d(tag: String, msg: String)
        fun i(tag: String, msg: String)
        fun w(tag: String, msg: String)
        fun e(tag: String, msg: String, tr: Throwable?)
    }

    private object AndroidLogger : Logger {
        override fun v(tag: String, msg: String) { Log.v(tag, msg) }
        override fun d(tag: String, msg: String) { Log.d(tag, msg) }
        override fun i(tag: String, msg: String) { Log.i(tag, msg) }
        override fun w(tag: String, msg: String) { Log.w(tag, msg) }
        override fun e(tag: String, msg: String, tr: Throwable?) { Log.e(tag, msg, tr) }
    }

    @Volatile var logger: Logger = AndroidLogger

    @Volatile var isMasterEnabled = false // Master toggle for all logs
    @Volatile var logCallRec = true // Call Recording specific logs
    @Volatile var logCallNotes = true // Call Notes specific logs

    // Initializes logging state from RemotePreferences during process attachment
    fun sync(module: XposedModule) {
        runCatching {
            val prefs = module.getRemotePreferences(IpcManager.PREF_NAME)
            isMasterEnabled = prefs.getBoolean(PreferenceKeys.ENABLE_MASTER_LOG, false)
            logCallRec = prefs.getBoolean(PreferenceKeys.LOG_CALL_RECORDING, true)
            logCallNotes = prefs.getBoolean(PreferenceKeys.LOG_CALL_NOTES, true)
            
            logger.i("DLTK_Hook", "[Logger] Settings synced. Master=$isMasterEnabled (PID: ${Process.myPid()})")
        }
    }

    // Handles real-time log toggle updates via IPC broadcasts
    fun handleBroadcast(intent: Intent) {
        val action = intent.action ?: return
        
        var isChanged = false
        var targetKey = ""
        var targetValue = false

        if (action == IpcManager.ACTION_SETTINGS_SYNC) {
            isMasterEnabled = intent.getBooleanExtra(PreferenceKeys.ENABLE_MASTER_LOG, false)
            logCallRec = intent.getBooleanExtra(PreferenceKeys.LOG_CALL_RECORDING, true)
            logCallNotes = intent.getBooleanExtra(PreferenceKeys.LOG_CALL_NOTES, true)
            isChanged = true
            targetKey = "ALL_SETTINGS"
            targetValue = isMasterEnabled
        } else if (action == IpcManager.ACTION_SETTING_CHANGED) {
            val key = intent.getStringExtra(PreferenceKeys.EXTRA_KEY) ?: return
            val value = intent.getBooleanExtra(PreferenceKeys.EXTRA_VALUE, true)
            when (key) {
                PreferenceKeys.ENABLE_MASTER_LOG -> if (isMasterEnabled != value) { isMasterEnabled = value; isChanged = true }
                PreferenceKeys.LOG_CALL_RECORDING -> if (logCallRec != value) { logCallRec = value; isChanged = true }
                PreferenceKeys.LOG_CALL_NOTES -> if (logCallNotes != value) { logCallNotes = value; isChanged = true }
            }
            targetKey = key
            targetValue = value
        }

        if (isChanged && (isMasterEnabled || targetKey == PreferenceKeys.ENABLE_MASTER_LOG)) {
            logger.i("DLTK_Hook", "[Success] Log setting [$targetKey] updated to $targetValue")
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun v(tag: String, status: String, msg: String) {
        if (isMasterEnabled && isSubEnabled(tag)) {
            runCatching { logger.v("DLTK_$tag", "[$status] $msg") }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun d(tag: String, status: String, msg: String) {
        if (isMasterEnabled && isSubEnabled(tag)) {
            runCatching { logger.d("DLTK_$tag", "[$status] $msg") }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun i(tag: String, status: String, msg: String) {
        if (isMasterEnabled && isSubEnabled(tag)) {
            runCatching { logger.i("DLTK_$tag", "[$status] $msg") }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun e(tag: String, status: String, msg: String, tr: Throwable? = null) {
        if (isMasterEnabled || tag == "Hook") {
            runCatching { logger.e("DLTK_$tag", "[$status] $msg", tr) }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun w(tag: String, status: String, msg: String) {
        if (isMasterEnabled && isSubEnabled(tag)) {
            runCatching { logger.w("DLTK_$tag", "[$status] $msg") }
        }
    }

    fun isSubEnabled(tag: String): Boolean {
        return when (tag) {
            "CallRec" -> logCallRec
            "CallNotes" -> logCallNotes
            else -> true
        }
    }
}
