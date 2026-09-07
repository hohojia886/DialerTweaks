package io.github.hohojia886.dialertweaks.hooks

import android.content.Context
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.ToneGenerator
import android.net.Uri
import android.os.Process
import io.github.hohojia886.dialertweaks.utils.IpcManager
import io.github.hohojia886.dialertweaks.utils.Logger
import io.github.hohojia886.dialertweaks.utils.PreferenceKeys
import io.github.hohojia886.dialertweaks.utils.hookAfter
import io.github.hohojia886.dialertweaks.utils.hookBefore
import io.github.libxposed.api.XposedModule
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Collections
import java.util.WeakHashMap

/**
 * CallNotesHook: Silences AI recording announcements.
 * Intercepts MediaPlayer and AudioTrack playbacks by analyzing stack traces 
 * for AI-related components like Fermat or SODA, then mutes the audio.
 */
object CallNotesHook {

    private const val TAG = "CallNotes"
    @Volatile private var isSilenceEnabled = true
    private var currentPkg = "unknown"
    
    // Performance: Cache instances identified as AI to avoid redundant stack trace scans
    private val mutedInstances = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    private fun isFermatCaller(context: String, instance: Any? = null): Boolean {
        if (instance != null && mutedInstances.contains(instance)) return true

        val stack = Thread.currentThread().stackTrace
        val isFermat = stack.any {
            val cls = it.className
            cls.contains("AudioInjector", true) ||
            cls.contains("Fermat", true) ||
            cls.contains("tidepods", true) ||
            cls.contains("callrecording", true) ||
            cls.contains("soda", true) ||
            cls.contains("intelligence", true) ||
            cls.contains("NotificationPlayer", true) ||
            cls.contains("transcript", true) ||
            cls.contains("recorder", true) ||
            (cls.contains("media", true) && currentPkg.contains("dialer"))
        }

        if (isFermat) {
            if (instance != null) mutedInstances.add(instance)
            Logger.e(TAG, "Active", "Identified AI Announcer via [$context] in $currentPkg")
        }
        
        return isFermat
    }

    private fun syncState(module: XposedModule, classLoader: ClassLoader? = null) {
        runCatching {
            val prefs = module.getRemotePreferences(IpcManager.PREF_NAME)
            var silenceEnabled = prefs.getBoolean(PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, true)
            
            // Fallback content provider query
            if (silenceEnabled && classLoader != null) {
                runCatching {
                    val ctx = IpcManager.getSafeContext(classLoader, "com.google.android.dialer") ?: IpcManager.getSystemContext(classLoader)
                    if (ctx != null) {
                        val uri = Uri.parse("content://io.github.hohojia886.dialertweaks")
                        val bundle = ctx.contentResolver.call(uri, "get", null, null)
                        if (bundle != null) {
                            silenceEnabled = bundle.getBoolean(PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, silenceEnabled)
                        }
                    }
                }
            }

            isSilenceEnabled = silenceEnabled
            Logger.i(TAG, "Sync", "Settings synced: silenceEnabled=$isSilenceEnabled")
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader, packageName: String) {
        currentPkg = packageName
        Logger.i(TAG, "Init", "Initializing CallNotesHook")
        syncState(module, classLoader)
        val moduleUid = module.getModuleApplicationInfo().uid

        if (Process.myUid() == 1000) {
            IpcManager.getSafeContext(classLoader, packageName)?.let { ctx ->
                registerReceiver(ctx, moduleUid)
            }
        }

        runCatching {
            if (packageName == "android") {
                val ssClass = runCatching { classLoader.loadClass("com.android.server.SystemServer") }.getOrNull()
                if (ssClass != null) {
                    module.hookBefore(ssClass.getDeclaredMethod("run")) {
                        IpcManager.getSystemContext(classLoader)?.let { registerReceiver(it, moduleUid) }
                    }
                }
            } else {
                val appClass = runCatching { classLoader.loadClass("android.app.Application") }.getOrNull()
                if (appClass != null) {
                    module.hookBefore(appClass.getDeclaredMethod("onCreate")) { chain ->
                        val app = chain.thisObject as? Context
                        if (app != null) registerReceiver(app, moduleUid)
                    }
                }
            }
        }

        hookMediaPlayer(module)
        hookAudioTrack(module)
        hookToneAndRingtone(module)
    }

    private fun hookMediaPlayer(module: XposedModule) {
        val mpClass = MediaPlayer::class.java
        mpClass.declaredMethods.filter { it.name == "start" || it.name == "prepare" || it.name == "prepareAsync" }.forEach { m ->
            runCatching {
                module.hookBefore(m) { chain ->
                    val instance = chain.thisObject ?: return@hookBefore
                    if (isSilenceEnabled && isFermatCaller("MediaPlayer.${m.name}", instance)) {
                        (instance as? MediaPlayer)?.runCatching { setVolume(0f, 0f) }
                    }
                }
            }
        }
    }

    private fun hookAudioTrack(module: XposedModule) {
        runCatching {
            AudioTrack::class.java.declaredConstructors.forEach { ctor ->
                module.hookAfter(ctor) { chain, _ ->
                    val instance = chain.thisObject ?: return@hookAfter
                    if (isSilenceEnabled && isFermatCaller("AudioTrackCtor", instance)) {
                        (instance as? AudioTrack)?.runCatching { setVolume(0f) }
                    }
                }
            }
        }
        
        AudioTrack::class.java.declaredMethods.filter { it.name == "write" }.forEach { m ->
            runCatching {
                module.hookBefore(m) { chain ->
                    val instance = chain.thisObject ?: return@hookBefore
                    if (isSilenceEnabled && isFermatCaller("AudioTrack.write", instance)) {
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
            module.hook(m).intercept { chain ->
                val instance = chain.thisObject
                if (instance != null && isSilenceEnabled && isFermatCaller("ToneGenerator", instance)) {
                    Logger.e(TAG, "Active", "Blocked ToneGenerator startTone from AI announcer")
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        runCatching {
            val m = Ringtone::class.java.getDeclaredMethod("play")
            module.hook(m).intercept { chain ->
                val instance = chain.thisObject
                if (instance != null && isSilenceEnabled && isFermatCaller("Ringtone", instance)) {
                    Logger.e(TAG, "Active", "Blocked Ringtone play from AI announcer")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun registerReceiver(context: Context, moduleUid: Int) {
        IpcManager.registerSecureReceiver(context, moduleUid) { intent ->
            val action = intent.action ?: return@registerSecureReceiver
            if (action == IpcManager.ACTION_SETTINGS_SYNC) {
                isSilenceEnabled = intent.getBooleanExtra(PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT, true)
            } else {
                val key = intent.getStringExtra(PreferenceKeys.EXTRA_KEY)
                if (key == PreferenceKeys.DISABLE_CALL_NOTES_ANNOUNCEMENT) {
                    isSilenceEnabled = intent.getBooleanExtra(PreferenceKeys.EXTRA_VALUE, true)
                }
            }
            mutedInstances.clear()
            Logger.i(TAG, "Sync", "isSilenceEnabled updated to: $isSilenceEnabled")
        }
        Logger.d(TAG, "Receiver", "Registered for $currentPkg")
    }
}
