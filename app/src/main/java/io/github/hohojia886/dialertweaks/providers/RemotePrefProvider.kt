package io.github.hohojia886.dialertweaks.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.hohojia886.dialertweaks.utils.IpcManager
import io.github.hohojia886.dialertweaks.utils.Logger

/**
 * RemotePrefProvider: A bridge between Credential-Encrypted (CE) and Device-Protected (DE) storage.
 * Provides a secure mechanism for hook processes (SystemUI, Dialer) to read/write module settings
 * before the user has unlocked the device (FBE support).
 */
class RemotePrefProvider : ContentProvider() {

    private val trustedUids = mutableSetOf<Int>()
    private val TAG = "Security"

    override fun onCreate(): Boolean = true

    private fun updateTrustedUids() {
        if (trustedUids.isNotEmpty()) return
        val ctx = context ?: return
        val pm = ctx.packageManager
        val packages = listOf(
            "com.android.systemui",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.google.android.as",
            "com.google.android.gms"
        )

        packages.forEach { pkg ->
            runCatching {
                pm.getPackageInfo(pkg, 0).applicationInfo?.uid?.let { trustedUids.add(it) }
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callingUid = Binder.getCallingUid()
        updateTrustedUids()

        // 1. Strict WRITE Control: Only the module app and trusted system components can modify settings
        if (method == "put") {
            val isModule = callingUid == Process.myUid()
            val isTrusted = callingUid == 1000 || trustedUids.contains(callingUid)

            if (isModule || isTrusted) {
                Logger.i(TAG, "Sync", "Allowed WRITE from UID: $callingUid")
                return handlePut(extras)
            }

            Logger.e(TAG, "Blocked", "Unauthorized WRITE from UID: $callingUid")
            return null
        }

        // 2. READ Control
        if (method == "get") {
            val isWhitelisted = callingUid < 1000 || trustedUids.contains(callingUid)
            if (!isWhitelisted) {
                Logger.w(TAG, "Warning", "UID $callingUid is reading prefs without whitelist")
            }
            return handleGet()
        }

        return null
    }

    private fun handleGet(): Bundle {
        val deContext = context?.createDeviceProtectedStorageContext() ?: return Bundle()
        val prefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, Context.MODE_PRIVATE)
        val bundle = Bundle()

        prefs.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> bundle.putBoolean(k, v)
                is Int -> bundle.putInt(k, v)
                is Long -> bundle.putLong(k, v)
                is Float -> bundle.putFloat(k, v)
                is String -> bundle.putString(k, v)
            }
        }
        return bundle
    }

    private fun handlePut(extras: Bundle?): Bundle {
        val deContext = context?.createDeviceProtectedStorageContext() ?: return Bundle()
        val prefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        
        extras?.keySet()?.forEach { k ->
            when (val v = extras.get(k)) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Float -> edit.putFloat(k, v)
                is String -> edit.putString(k, v)
            }
        }
        edit.apply()
        return Bundle().apply { putBoolean("success", true) }
    }

    override fun query(uri: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
