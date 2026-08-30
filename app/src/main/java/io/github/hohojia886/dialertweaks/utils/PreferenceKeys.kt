package io.github.hohojia886.dialertweaks.utils

/**
 * Single source of truth for every settings key used across the module.
 */
object PreferenceKeys {

    /** Generic broadcast extra field names, shared by every single-key update. */
    const val EXTRA_KEY = "key"
    const val EXTRA_VALUE = "value"

    // ---- Feature toggles -------------------------------------------------
    const val ENABLE_CALL_RECORDING = "enable_call_recording"
    const val DISABLE_VOICE_ANNOUNCEMENT = "disable_voice_announcement"
    const val DISABLE_CALL_NOTES_ANNOUNCEMENT = "disable_call_notes_announcement"

    // ---- Debug logging configuration ---------------------------------------
    const val ENABLE_MASTER_LOG = "enable_master_log"
    const val LOG_CALL_RECORDING = "log_call_recording"
}
