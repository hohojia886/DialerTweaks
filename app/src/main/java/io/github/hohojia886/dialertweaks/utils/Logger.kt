package io.github.hohojia886.dialertweaks.utils

import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * Standardized Logging Utility.
 */
object Logger {

    interface Logger {
        fun i(tag: String, msg: String)
        fun w(tag: String, msg: String)
        fun e(tag: String, msg: String, tr: Throwable?)
    }

    private object AndroidLogger : Logger {
        override fun i(tag: String, msg: String) { Log.i(tag, msg) }
        override fun w(tag: String, msg: String) { Log.w(tag, msg) }
        override fun e(tag: String, msg: String, tr: Throwable?) { Log.e(tag, msg, tr) }
    }

    @Volatile var logger: Logger = AndroidLogger

    @Volatile var isMasterEnabled = true
    @Volatile var logCallRec = true

    fun sync(module: XposedModule) {
        runCatching {
            val prefs = module.getRemotePreferences(IpcManager.PREF_NAME)
            // Default to TRUE for development visibility
            isMasterEnabled = prefs.getBoolean(PreferenceKeys.ENABLE_MASTER_LOG, true)
            logCallRec = prefs.getBoolean(PreferenceKeys.LOG_CALL_RECORDING, true)
            
            Log.e("DT_Hook", "[Logger] Settings synced. Master=$isMasterEnabled, CallRec=$logCallRec (PID: ${android.os.Process.myPid()})")
        }.onFailure { e ->
            Log.e("DT_Hook", "[Logger] Sync failed", e)
        }
    }

    fun handleBroadcast(intent: Intent) {
        val action = intent.action ?: return
        
        var isChanged = false
        var targetKey = ""
        var targetValue = false

        if (action == IpcManager.ACTION_SETTINGS_SYNC) {
            isMasterEnabled = intent.getBooleanExtra(PreferenceKeys.ENABLE_MASTER_LOG, false)
            logCallRec = intent.getBooleanExtra(PreferenceKeys.LOG_CALL_RECORDING, true)
            isChanged = true
            targetKey = "ALL_SETTINGS"
            targetValue = isMasterEnabled
        } else if (action == IpcManager.ACTION_SETTING_CHANGED) {
            val key = intent.getStringExtra(PreferenceKeys.EXTRA_KEY) ?: return
            val value = intent.getBooleanExtra(PreferenceKeys.EXTRA_VALUE, true)
            when (key) {
                PreferenceKeys.ENABLE_MASTER_LOG -> if (isMasterEnabled != value) { isMasterEnabled = value; isChanged = true }
                PreferenceKeys.LOG_CALL_RECORDING -> if (logCallRec != value) { logCallRec = value; isChanged = true }
            }
            targetKey = key
            targetValue = value
        }

        if (isChanged) {
            Log.e("DT_Hook", "[Logger] Setting [$targetKey] updated to $targetValue via broadcast")
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun i(tag: String, status: String, msg: String) {
        if (isMasterEnabled && isSubEnabled(tag)) {
            runCatching { logger.i("DT_$tag", "[$status] $msg") }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun e(tag: String, status: String, msg: String, tr: Throwable? = null) {
        runCatching {
            logger.e("DT_$tag", "[$status] $msg", tr)
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun w(tag: String, status: String, msg: String) {
        if (isMasterEnabled && isSubEnabled(tag)) {
            runCatching {
                logger.w("DT_$tag", "[$status] $msg")
            }
        }
    }

    fun isSubEnabled(tag: String): Boolean {
        return when (tag) {
            "CallRec" -> logCallRec
            else -> true
        }
    }
}
