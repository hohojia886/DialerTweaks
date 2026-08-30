package io.github.hohojia886.dialertweaks.hooks

import android.content.Context
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import android.media.Ringtone
import android.util.Log
import io.github.hohojia886.dialertweaks.utils.IpcManager
import io.github.hohojia886.dialertweaks.utils.Logger
import io.github.hohojia886.dialertweaks.utils.PreferenceKeys
import io.github.hohojia886.dialertweaks.utils.hookAfter
import io.github.hohojia886.dialertweaks.utils.hookBefore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * Finalized Silence Module for Fermat / Call Notes / SODA.
 * Uses stack-trace pattern matching to identify and mute recording announcements.
 */
object CallNotesHook {

    private const val TAG = "DT_CallNotes"
    @Volatile private var isSilenceEnabled = true
    private var currentPkg = "unknown"

    /**
     * Precision Identification of Fermat/Call-Notes callers.
     */
    private fun isFermatCaller(context: String): Boolean {
        val stack = Thread.currentThread().stackTrace
        var matchedKeyword = ""
        val isFermat = stack.any {
            val cls = it.className
            val match = when {
                cls.contains("AudioInjector", true) -> "AudioInjector"
                cls.contains("Fermat", true) -> "Fermat"
                cls.contains("tidepods", true) -> "tidepods"
                cls.contains("callrecording", true) -> "callrecording"
                cls.contains("soda", true) -> "soda"
                cls.contains("intelligence", true) -> "intelligence"
                cls.contains("NotificationPlayer", true) -> "NotificationPlayer"
                // Match obfuscated media callers in Dialer (e.g. oea.c, hsk.b)
                cls.contains("media", true) && currentPkg.contains("dialer") -> "DialerMedia"
                else -> null
            }
            if (match != null) matchedKeyword = match
            match != null
        }

        if (isFermat) {
            Log.e(TAG, "[$currentPkg] Fermat Silenced ($matchedKeyword) via [$context]")
        }
        
        return isFermat
    }

    private fun syncState(module: XposedModule) {
        runCatching {
            val prefs = module.getRemotePreferences(IpcManager.PREF_NAME)
            isSilenceEnabled = prefs.getBoolean(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true)
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader, packageName: String) {
        currentPkg = packageName
        syncState(module)
        val moduleUid = module.getModuleApplicationInfo().uid

        // Secure context acquisition
        runCatching {
            val appClass = if (packageName == "android") "com.android.server.SystemServer" else "android.app.Application"
            val method = if (packageName == "android") "run" else "onCreate"
            module.hook(classLoader.loadClass(appClass).getDeclaredMethod(method)).intercept { chain ->
                val ctx = if (packageName == "android") IpcManager.getSystemContext(classLoader) else chain.thisObject as? Context
                if (ctx != null) registerReceiver(ctx, moduleUid)
                chain.proceed()
            }
        }

        // --- Execute Stealth Muting ---
        hookMediaPlayer(module)
        hookAudioTrack(module)
        hookToneAndRingtone(module)
    }

    private fun hookMediaPlayer(module: XposedModule) {
        val mpClass = MediaPlayer::class.java
        // Hook all critical playback start points
        mpClass.declaredMethods.filter { it.name == "start" || it.name == "prepare" || it.name == "prepareAsync" }.forEach { m ->
            runCatching {
                module.hookBefore(m) { chain ->
                    if (isSilenceEnabled && isFermatCaller("MediaPlayer.${m.name}")) {
                        (chain.thisObject as? MediaPlayer)?.runCatching { setVolume(0f, 0f) }
                    }
                }
            }
        }
    }

    private fun hookAudioTrack(module: XposedModule) {
        // Plan A: Instance Muting
        runCatching {
            AudioTrack::class.java.declaredConstructors.forEach { ctor ->
                module.hookAfter(ctor) { chain, _ ->
                    if (isSilenceEnabled && isFermatCaller("AudioTrackCtor")) {
                        (chain.thisObject as? AudioTrack)?.runCatching { setVolume(0f) }
                    }
                }
            }
        }
        // Plan C: PCM Zeroing (Safety net)
        AudioTrack::class.java.declaredMethods.filter { it.name == "write" }.forEach { m ->
            runCatching {
                module.hookBefore(m) { chain ->
                    if (isSilenceEnabled && isFermatCaller("AudioTrack.write")) {
                        when (val buf = chain.args[0]) {
                            is ByteArray -> Arrays.fill(buf, 0.toByte())
                            is ShortArray -> Arrays.fill(buf, 0.toShort())
                            is ByteBuffer -> if (!buf.isReadOnly) {
                                val size = if (chain.args.size > 1 && chain.args[1] is Int) chain.args[1] as Int else buf.remaining()
                                val p = buf.position()
                                for (i in 0 until size) if (p + i < buf.capacity()) buf.put(p + i, 0.toByte())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookToneAndRingtone(module: XposedModule) {
        runCatching {
            val m = ToneGenerator::class.java.getDeclaredMethod("startTone", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            module.hookBefore(m) { if (isSilenceEnabled && isFermatCaller("ToneGenerator")) { /* Blocked */ } }
        }
        runCatching {
            val m = Ringtone::class.java.getDeclaredMethod("play")
            module.hookBefore(m) { if (isSilenceEnabled && isFermatCaller("Ringtone")) { /* Blocked */ } }
        }
    }

    private fun registerReceiver(context: Context, moduleUid: Int) {
        IpcManager.registerSecureReceiver(context, moduleUid) { intent ->
            isSilenceEnabled = intent.getBooleanExtra(PreferenceKeys.EXTRA_VALUE, true)
            Log.d(TAG, "[$currentPkg] Updated isSilenceEnabled: $isSilenceEnabled")
        }
    }
}
