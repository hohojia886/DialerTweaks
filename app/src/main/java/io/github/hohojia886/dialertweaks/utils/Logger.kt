package io.github.hohojia886.dialertweaks.utils

import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedModuleInterface

object Logger {
    private const val TAG = "DialerTweaks"
    private var isMasterLogEnabled = true

    fun sync(module: XposedModuleInterface) {
        runCatching {
            val prefs = module.getRemotePreferences("io.github.hohojia886.dialertweaks")
            isMasterLogEnabled = prefs.getBoolean("enable_master_log", true)
        }
    }

    fun handleBroadcast(intent: Intent) {
        if (intent.getStringExtra("key") == "enable_master_log") {
            isMasterLogEnabled = intent.getBooleanExtra("value", true)
        }
    }

    fun i(tag: String, type: String, msg: String) {
        if (isMasterLogEnabled) Log.i(TAG, "[$tag][$type] $msg")
    }

    fun w(tag: String, type: String, msg: String) {
        if (isMasterLogEnabled) Log.w(TAG, "[$tag][$type] $msg")
    }

    fun e(tag: String, type: String, msg: String, t: Throwable? = null) {
        if (isMasterLogEnabled) Log.e(TAG, "[$tag][$type] $msg", t)
    }
}
