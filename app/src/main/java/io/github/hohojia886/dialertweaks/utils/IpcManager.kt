package io.github.hohojia886.dialertweaks.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import java.lang.ref.WeakReference

/**
 * IpcManager: Orchestrates cross-process communication and settings synchronization.
 * Manages secure broadcast registration, system context retrieval via reflection,
 * and ensures that all hook instances across different processes stay in sync with the UI.
 */
object IpcManager {
    const val PREF_NAME = "io.github.hohojia886.dialertweaks"
    const val ACTION_SETTING_CHANGED = "io.github.hohojia886.dialertweaks.SETTING_CHANGED"
    const val ACTION_SETTINGS_SYNC = "io.github.hohojia886.dialertweaks.SETTINGS_SYNC"
    const val PERMISSION_SYNC_SETTINGS = "io.github.hohojia886.dialertweaks.permission.SYNC_SETTINGS"

    private var sysContextRef: WeakReference<Context>? = null // Cached system context

    // Retrieves the underlying system context using ActivityThread reflection
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

    // Obtains a context suitable for ContentProvider calls, matching the current process identity
    fun getSafeContext(classLoader: ClassLoader, packageName: String? = null): Context? {
        return runCatching {
            val atClass = classLoader.loadClass("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            val app = atClass.getDeclaredMethod("getApplication").invoke(at) as? Context
            
            if (app != null) return app

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

    // Dispatches a full settings synchronization broadcast to all active hook processes
    @SuppressLint("WrongConstant")
    fun syncAllSettings(context: Context, prefs: SharedPreferences) {
        val intent = Intent(ACTION_SETTINGS_SYNC).apply {
            // 1. CallRec
            putExtra(PreferenceKeys.ENABLE_CALL_RECORDING, prefs.getBoolean(PreferenceKeys.ENABLE_CALL_RECORDING, true))
            putExtra(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, prefs.getBoolean(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true))
            putExtra(PreferenceKeys.LOG_CALL_RECORDING, prefs.getBoolean(PreferenceKeys.LOG_CALL_RECORDING, true))

            // 2. CallNotes
            putExtra(PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, prefs.getBoolean(PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, true))
            putExtra(PreferenceKeys.LOG_CALL_NOTES, prefs.getBoolean(PreferenceKeys.LOG_CALL_NOTES, true))

            // General / Debug
            putExtra(PreferenceKeys.ENABLE_MASTER_LOG, prefs.getBoolean(PreferenceKeys.ENABLE_MASTER_LOG, false))

            addFlags(0x01000000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
        }
        context.sendBroadcast(intent)
    }

    // Dispatches a broadcast for a single preference change to minimize IPC overhead
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

    // Registers a receiver with UID verification to ensure settings are only accepted from trusted sources
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

                    if (senderUid == 1000 || senderUid == moduleUid || senderUid == Process.myUid() || senderUid == -1) {
                        Logger.handleBroadcast(intent)
                        onVerifiedBroadcast(intent)
                    } else {
                        Log.w("DLTK_Secure", "Rejected broadcast from unauthorized UID: $senderUid")
                    }
                }
            }
            val targetContext = context.applicationContext ?: context
            targetContext.registerReceiver(receiver, filter, null, null, Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            Log.wtf("DLTK_Secure", "CRITICAL: Receiver registration failed", t)
        }
    }
}
