package io.github.hohojia886.dialertweaks.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.hohojia886.dialertweaks.utils.IpcManager

class RemotePrefProvider : ContentProvider() {

    private val trustedUids = mutableSetOf<Int>()
    private var lastUpdate = 0L
    private val CACHE_TIMEOUT = 300_000L

    override fun onCreate(): Boolean = true

    private fun updateTrustedUids() {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < CACHE_TIMEOUT && trustedUids.isNotEmpty()) return

        synchronized(trustedUids) {
            trustedUids.clear()
            trustedUids.add(1000)
            trustedUids.add(Process.myUid())

            val pm = context?.packageManager ?: return
            val packages = listOf(
                "com.google.android.dialer",
                "com.android.dialer"
            )

            packages.forEach { pkg ->
                runCatching {
                    pm.getPackageInfo(pkg, 0)?.applicationInfo?.uid?.let { trustedUids.add(it) }
                }
            }
            lastUpdate = now
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callingUid = Binder.getCallingUid()
        updateTrustedUids()

        if (method == "put") {
            if (callingUid != Process.myUid()) return null
            return handlePut(extras)
        }

        if (method == "get") {
            val isWhitelisted = callingUid < 1000 || trustedUids.contains(callingUid)
            if (!isWhitelisted) return null
            return handleGet()
        }

        return null
    }

    private fun handleGet(): Bundle? {
        val deContext = context?.createDeviceProtectedStorageContext() ?: return null
        val prefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, 0)
        val res = Bundle()
        prefs.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> res.putBoolean(k, v)
                is Int -> res.putInt(k, v)
            }
        }
        return res
    }

    private fun handlePut(extras: Bundle?): Bundle? {
        val deContext = context?.createDeviceProtectedStorageContext() ?: return null
        val prefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, 0)
        val edit = prefs.edit()
        extras?.keySet()?.forEach { k ->
            val v = extras.get(k)
            when (v) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
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
