package io.github.hohojia886.dialertweaks.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Process
import java.lang.ref.WeakReference

/**
 * Utilities for cross-process communication and settings synchronization.
 * Handles both standard synchronization and secure broadcast management.
 */
object IpcManager {
    const val PREF_NAME = "io.github.hohojia886.dialertweaks"
    const val ACTION_SETTING_CHANGED = "io.github.hohojia886.dialertweaks.SETTING_CHANGED"
    const val ACTION_SETTINGS_SYNC = "io.github.hohojia886.dialertweaks.SETTINGS_SYNC"
    const val PERMISSION_SYNC_SETTINGS = "io.github.hohojia886.dialertweaks.permission.SYNC_SETTINGS"

    private var sysContextRef: WeakReference<Context>? = null

    /**
     * Unified helper to get System Context via reflection.
     */
    fun getSystemContext(classLoader: ClassLoader): Context? {
        sysContextRef?.get()?.let { return it }
        return runCatching {
            val atClass = classLoader.loadClass("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            val context = atClass.getDeclaredMethod("getSystemContext").invoke(at) as? Context
            context?.let { sysContextRef = WeakReference(it) }
            context
        }.getOrNull()
    }

    /**
     * Gets a context suitable for ContentProvider calls (matching the current process UID).
     */
    fun getSafeContext(classLoader: ClassLoader, packageName: String? = null): Context? {
        return runCatching {
            val atClass = classLoader.loadClass("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            val app = atClass.getDeclaredMethod("getApplication").invoke(at) as? Context
            
            if (app != null) return app

            // If application is null, try to create a context for the current process
            val sysContext = atClass.getDeclaredMethod("getSystemContext").invoke(at) as? Context ?: return null
            
            val myUid = Process.myUid()
            val targetPackage = packageName ?: runCatching {
                val ipmClass = classLoader.loadClass("android.app.AppGlobals")
                val ipm = ipmClass.getDeclaredMethod("getPackageManager").invoke(null) ?: return@runCatching null
                val getPackagesMethod = ipm.javaClass.getDeclaredMethod("getPackagesForUid", Int::class.javaPrimitiveType)
                val packages = getPackagesMethod.invoke(ipm, myUid) as? Array<*>
                packages?.get(0) as? String
            }.getOrNull()

            if (myUid != 1000 && targetPackage != null && targetPackage != "android") {
                sysContext.createPackageContext(targetPackage, 0)
            } else {
                sysContext
            }
        }.getOrNull()
    }

    /**
     * Sends a full settings synchronization broadcast to all listening hook processes.
     */
    @SuppressLint("WrongConstant")
    fun syncAllSettings(context: Context, prefs: SharedPreferences) {
        val intent = Intent(ACTION_SETTINGS_SYNC).apply {
            putExtra(PreferenceKeys.ENABLE_CALL_RECORDING, prefs.getBoolean(PreferenceKeys.ENABLE_CALL_RECORDING, true))
            putExtra(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, prefs.getBoolean(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true))
            
            // Debug Logs Configuration
            putExtra(PreferenceKeys.ENABLE_MASTER_LOG, prefs.getBoolean(PreferenceKeys.ENABLE_MASTER_LOG, false))
            putExtra(PreferenceKeys.LOG_CALL_RECORDING, prefs.getBoolean(PreferenceKeys.LOG_CALL_RECORDING, true))

            addFlags(0x01000000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
        }
        context.sendBroadcast(intent)
    }

    /**
     * Sends a single-key update broadcast when a specific setting is toggled.
     */
    @SuppressLint("WrongConstant")
    fun sendUpdateBroadcast(context: Context, key: String, value: Any) {
        val intent = Intent(ACTION_SETTING_CHANGED).apply {
            putExtra(PreferenceKeys.EXTRA_KEY, key)
            when (value) {
                is Boolean -> putExtra(PreferenceKeys.EXTRA_VALUE, value)
                is Int -> putExtra(PreferenceKeys.EXTRA_VALUE, value)
                is Float -> putExtra(PreferenceKeys.EXTRA_VALUE, value)
                is Long -> putExtra(PreferenceKeys.EXTRA_VALUE, value)
            }
            addFlags(0x01000000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
        }
        context.sendBroadcast(intent)
    }

    /**
     * Standard sync registration. Uses signature-level protection.
     */
    fun registerSecureReceiver(
        context: Context,
        moduleUid: Int,
        extraActions: List<String> = emptyList(),
        onVerifiedBroadcast: (intent: Intent) -> Unit
    ) {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_SETTING_CHANGED)
                addAction(ACTION_SETTINGS_SYNC)
                extraActions.forEach { addAction(it) }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val senderUid = runCatching {
                        val method = BroadcastReceiver::class.java.getDeclaredMethod("getSendingUid")
                        method.isAccessible = true
                        method.invoke(this) as Int
                    }.getOrDefault(-1)

                    // Trusted: System (1000), Module, or current process
                    // Note: senderUid might be -1 on some devices for dynamic receivers.
                    if (senderUid == 1000 || senderUid == moduleUid || senderUid == Process.myUid() || senderUid == -1) {
                        runCatching { Logger.handleBroadcast(intent) }
                        onVerifiedBroadcast(intent)
                    } else {
                        android.util.Log.w("DT_Secure", "Rejected broadcast from unauthorized UID: $senderUid")
                    }
                }
            }
            val targetContext = context.applicationContext ?: context
            // Manual UID verification is performed in onReceive, so we can pass null for permission 
            // to ensure maximum compatibility with system-level background processes.
            targetContext.registerReceiver(receiver, filter, null, null, Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            android.util.Log.wtf("DT_Secure", "CRITICAL: Receiver registration failed", t)
        }
    }
}
