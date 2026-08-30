package io.github.hohojia886.dialertweaks.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hohojia886.dialertweaks.utils.IpcManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_USER_UNLOCKED) {
            
            val deContext = context.createDeviceProtectedStorageContext()
            val prefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, Context.MODE_PRIVATE)
            IpcManager.syncAllSettings(context, prefs)
        }
    }
}
