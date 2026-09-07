package io.github.hohojia886.dialertweaks.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hohojia886.dialertweaks.utils.IpcManager

/**
 * BootReceiver: Triggered upon device startup or user unlock.
 * Reads saved settings from Device-Protected (DE) storage and broadcasts them to hook processes.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED) {
            
            val deContext = context.createDeviceProtectedStorageContext()
            val prefs = deContext.getSharedPreferences(IpcManager.PREF_NAME, Context.MODE_PRIVATE)
            IpcManager.syncAllSettings(context, prefs)
        }
    }
}
