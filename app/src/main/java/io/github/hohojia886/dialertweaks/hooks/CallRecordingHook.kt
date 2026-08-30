package io.github.hohojia886.dialertweaks.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import io.github.hohojia886.dialertweaks.utils.IpcManager
import io.github.hohojia886.dialertweaks.utils.Logger
import io.github.hohojia886.dialertweaks.utils.PreferenceKeys
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * High-Performance Call Recording Hook for Google Dialer.
 */
@SuppressLint("DiscouragedApi", "SoonBlockedPrivateApi")
object CallRecordingHook {

    private const val TAG = "CallRec"
    private const val CACHE_FILE = "call_rec_v1.cache"
    private const val VERSION = "1.0.0"

    private val DEX_KEYWORDS = listOf(
        "canRecordCall", "Crosby", "GeoFence", "isCallRecordingCountry"
    )

    @Volatile private var isRecordingEnabled = true
    @Volatile private var isSilenceEnabled = true
    private var receiverRegistered = false
    private var sessionRetryCount = 0
    
    @Volatile private var lastListener: WeakReference<UtteranceProgressListener>? = null
    @Volatile private var startId = -1
    @Volatile private var endId = -1

    private fun buildSilentWav(sampleRate: Int = 8000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataSize = 0 
        val buffer = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        return buffer.array()
    }

    private fun syncState(module: XposedModule) {
        runCatching {
            val prefs = module.getRemotePreferences(IpcManager.PREF_NAME)
            isRecordingEnabled = prefs.getBoolean(PreferenceKeys.ENABLE_CALL_RECORDING, true)
            isSilenceEnabled = prefs.getBoolean(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true)
            Logger.i(TAG, "Sync", "State synced: recording=$isRecordingEnabled, silence=$isSilenceEnabled")
            Logger.sync(module)
        }.onFailure { e ->
            Logger.e(TAG, "Error", "Failed to sync state via RemotePrefProvider", e)
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader, packageName: String) {
        Logger.i(TAG, "Init", "Initializing CallRecording module v$VERSION")
        syncState(module)
        val moduleUid = module.getModuleApplicationInfo().uid

        try {
            // 1. Telephony ISO Hook
            // 1. Telephony ISO Hook
            val tm = TelephonyManager::class.java
            val isoInterceptor: (XposedInterface.Chain) -> Any? = { chain ->
                if (isRecordingEnabled) "us" else chain.proceed()
            }

            runCatching {
                module.hook(tm.getDeclaredMethod("getSimCountryIso")).intercept(isoInterceptor)
                module.hook(tm.getDeclaredMethod("getNetworkCountryIso")).intercept(isoInterceptor)
                module.hook(tm.getDeclaredMethod("getSimCountryIso", Int::class.javaPrimitiveType)).intercept(isoInterceptor)
                module.hook(tm.getDeclaredMethod("getNetworkCountryIso", Int::class.javaPrimitiveType)).intercept(isoInterceptor)
            }.onFailure { e ->
                Logger.e(TAG, "Error", "Telephony ISO hooks failed", e)
            }

            // 2. Application Lifecycle
            module.hook(classLoader.loadClass("android.app.Application").getDeclaredMethod("onCreate")).intercept { chain ->
                val app = chain.thisObject as? Context
                if (app != null) {
                    registerReceiver(app, moduleUid)
                    syncState(module)
                }
                chain.proceed()
            }

            // 3. Resource Hook (getString)
            module.hook(Resources::class.java.getDeclaredMethod("getString", Int::class.java)).intercept { chain ->
                if (!isRecordingEnabled || !isSilenceEnabled) return@intercept chain.proceed()
                val res = chain.thisObject as Resources
                if (startId == -1) {
                    startId = res.getIdentifier("call_recording_starting_voice", "string", packageName)
                    endId = res.getIdentifier("call_recording_ending_voice", "string", packageName)
                }
                val resId = chain.args[0] as Int
                if (resId != 0 && (resId == startId || resId == endId)) {
                    Logger.i(TAG, "Active", "Muting voice announcement (getString): $resId")
                    ""
                } else chain.proceed()
            }

            // 4. Resource Hook (getText)
            runCatching {
                module.hook(Resources::class.java.getDeclaredMethod("getText", Int::class.java)).intercept { chain ->
                    if (!isRecordingEnabled || !isSilenceEnabled) return@intercept chain.proceed()
                    val res = chain.thisObject as Resources
                    if (startId == -1) {
                        startId = res.getIdentifier("call_recording_starting_voice", "string", packageName)
                        endId = res.getIdentifier("call_recording_ending_voice", "string", packageName)
                    }
                    val resId = chain.args[0] as Int
                    if (resId != 0 && (resId == startId || resId == endId)) {
                        Logger.i(TAG, "Active", "Muting voice announcement (getText): $resId")
                        ""
                    } else chain.proceed()
                }
            }.onFailure { e ->
                Logger.e(TAG, "Error", "Resources.getText hook failed", e)
            }

            hookTtsHooks(module)
        } catch (e: Throwable) {
            Logger.e(TAG, "Error", "Framework hook application failed", e)
        }
    }

    fun hookFull(module: XposedModule, classLoader: ClassLoader, packageName: String, targetSourceDir: String?) {
        hook(module, classLoader, packageName)
        if (targetSourceDir == null) {
            Logger.e(TAG, "Error", "Cannot run DexKit: targetSourceDir is null")
            return
        }

        val cacheFile = File(module.getModuleApplicationInfo().dataDir, CACHE_FILE)
        val currentVersion = try {
            IpcManager.getSystemContext(classLoader)?.packageManager?.getPackageInfo(packageName, 0)?.longVersionCode ?: 0L
        } catch (_: Exception) { 0L }

        if (loadFromCache(module, classLoader, cacheFile, currentVersion)) {
            Logger.i(TAG, "Hook", "Call recording flags applied from cache")
            return
        }

        sessionRetryCount = 0
        Thread {
            while (sessionRetryCount < 3) {
                try {
                    Logger.i(TAG, "Hook", "Starting background DexKit scan (Attempt ${sessionRetryCount + 1})")
                    performDexKitScan(module, classLoader, targetSourceDir, cacheFile, currentVersion)
                    Logger.i(TAG, "Hook", "DexKit scan completed and hooks applied")
                    return@Thread
                } catch (e: Exception) {
                    sessionRetryCount++
                    Logger.e(TAG, "Error", "DexKit scan attempt failed", e)
                    if (sessionRetryCount < 3) Thread.sleep(2000)
                }
            }
        }.start()
    }

    private fun performDexKitScan(module: XposedModule, classLoader: ClassLoader, targetSourceDir: String, cacheFile: File, version: Long) {
        val moduleLibDir = module.getModuleApplicationInfo().nativeLibraryDir
        val dexKitLib = File(moduleLibDir, "libdexkit.so")
        if (dexKitLib.exists()) { @Suppress("UnsafeDynamicallyLoadedCode") System.load(dexKitLib.absolutePath) }
        else { runCatching { System.loadLibrary("dexkit") } }

        val foundMethods = mutableListOf<String>()
        foundMethods.add("VERSION|$version")

        DexKitBridge.create(targetSourceDir).use { bridge ->
            DEX_KEYWORDS.forEach { word ->
                val candidates = bridge.findMethod { matcher { usingStrings(word); returnType = "boolean" } }
                candidates.forEach { data ->
                    runCatching {
                        val method = data.getMethodInstance(classLoader)
                        Logger.i(TAG, "Hook", "Found flag '$word' -> ${data.className}#${data.methodName}")
                        module.hook(method).intercept {
                            if (isRecordingEnabled) true else it.proceed()
                        }
                        foundMethods.add("FLAG|$word|${data.className}#${data.methodName}")
                    }.onFailure { e ->
                        Logger.w(TAG, "Hook", "Failed to hook candidate for '$word': ${e.message}")
                    }
                }
            }

            val localeCands = bridge.findMethod { matcher { usingStrings("getSupportedLocaleFromCountryCode"); returnType = "java.util.Locale" } }
            localeCands.firstOrNull()?.let { data ->
                runCatching {
                    val m = data.getMethodInstance(classLoader)
                    Logger.i(TAG, "Hook", "Found LocaleProvider -> ${data.className}#${data.methodName}")
                    module.hook(m).intercept { if (isRecordingEnabled) Locale.US else it.proceed() }
                    foundMethods.add("LOCALE|${data.className}#${data.methodName}")
                }.onFailure { e ->
                    Logger.w(TAG, "Hook", "Failed to hook locale provider: ${e.message}")
                }
            }
        }
        
        if (foundMethods.size > 1) {
            runCatching { cacheFile.writeText(foundMethods.joinToString("\n")) }
        } else {
            throw Exception("No valid methods found during DexKit scan")
        }
    }

    private fun loadFromCache(module: XposedModule, cl: ClassLoader, cacheFile: File, currentVersion: Long): Boolean {
        if (!cacheFile.exists()) return false
        return runCatching {
            val lines = cacheFile.readLines()
            if (lines.isEmpty()) return false

            val firstLine = lines[0].split("|")
            if (firstLine.size != 2 || firstLine[0] != "VERSION" || firstLine[1].toLong() != currentVersion) return false

            lines.drop(1).forEach { line ->
                runCatching {
                    val parts = line.split("|")
                    if (parts.size < 2) return@forEach
                    val type = parts[0]
                    val methodDesc = parts.last()
                    val mParts = methodDesc.split("#")
                    val clazz = cl.loadClass(mParts[0])
                    val method = clazz.declaredMethods.find { it.name == mParts[1] } ?: return@forEach

                    module.hook(method).intercept { chain ->
                        when (type) {
                            "LOCALE" -> if (isRecordingEnabled) Locale.US else chain.proceed()
                            "FLAG" -> if (isRecordingEnabled) true else chain.proceed()
                            else -> chain.proceed()
                        }
                    }
                }.onFailure { e ->
                    Logger.w(TAG, "Hook", "Failed to apply cached hook line: $line (${e.message})")
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun hookTtsHooks(module: XposedModule) {
        val ctorInterceptor: (XposedInterface.Chain) -> Any? = { chain ->
            val listener = chain.args[1] as? TextToSpeech.OnInitListener
            if (isRecordingEnabled && isSilenceEnabled && listener != null) {
                Logger.i(TAG, "Active", "Hijacking TTS initialization -> SUCCESS")
                runCatching { listener.onInit(TextToSpeech.SUCCESS) }
            }
            chain.proceed()
        }

        runCatching {
            val c1 = TextToSpeech::class.java.getDeclaredConstructor(Context::class.java, TextToSpeech.OnInitListener::class.java)
            module.hook(c1).intercept(ctorInterceptor)
            val c2 = TextToSpeech::class.java.getDeclaredConstructor(Context::class.java, TextToSpeech.OnInitListener::class.java, String::class.java)
            module.hook(c2).intercept(ctorInterceptor)
        }.onFailure { e ->
            Logger.e(TAG, "Error", "TTS constructor hooks failed", e)
        }

        runCatching {
            val m = TextToSpeech::class.java.getDeclaredMethod("isLanguageAvailable", Locale::class.java)
            module.hook(m).intercept { TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE }
        }.onFailure { e ->
            Logger.e(TAG, "Error", "isLanguageAvailable hook failed", e)
        }

        runCatching {
            val m = TextToSpeech::class.java.getDeclaredMethod("setOnUtteranceProgressListener", UtteranceProgressListener::class.java)
            module.hook(m).intercept { chain ->
                lastListener = (chain.args[0] as? UtteranceProgressListener)?.let { WeakReference(it) }
                chain.proceed()
            }
        }.onFailure { e ->
            Logger.e(TAG, "Error", "setOnUtteranceProgressListener hook failed", e)
        }

        val speakInterceptor: (XposedInterface.Chain) -> Any? = { chain ->
            if (isRecordingEnabled && isSilenceEnabled) {
                val utteranceId = chain.args[3] as? String
                val targetFile = if (chain.args.size >= 3 && chain.args[2] is File) chain.args[2] as File else null
                
                Logger.i(TAG, "Active", "Bypassing TTS playback: $utteranceId")

                if (targetFile != null) {
                    runCatching { targetFile.outputStream().use { it.write(buildSilentWav()) } }
                }

                lastListener?.get()?.let { listener ->
                    utteranceId?.let { id ->
                        runCatching {
                            listener.onStart(id)
                            listener.onDone(id)
                        }
                    }
                }
                TextToSpeech.SUCCESS
            } else {
                chain.proceed()
            }
        }

        runCatching {
            val mSpeak = TextToSpeech::class.java.getDeclaredMethod("speak", CharSequence::class.java, Int::class.javaPrimitiveType, Bundle::class.java, String::class.java)
            module.hook(mSpeak).intercept(speakInterceptor)
            val vSynth = TextToSpeech::class.java.getDeclaredMethod("synthesizeToFile", CharSequence::class.java, Bundle::class.java, File::class.java, String::class.java)
            module.hook(vSynth).intercept(speakInterceptor)
        }.onFailure { e ->
            Logger.e(TAG, "Error", "TTS playback hooks failed", e)
        }
    }

    private fun registerReceiver(context: Context, moduleUid: Int) {
        if (receiverRegistered) return
        IpcManager.registerSecureReceiver(context, moduleUid) { intent ->
            val action = intent.action ?: return@registerSecureReceiver
            if (action == IpcManager.ACTION_SETTINGS_SYNC) {
                isSilenceEnabled = intent.getBooleanExtra(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true)
                isRecordingEnabled = intent.getBooleanExtra(PreferenceKeys.ENABLE_CALL_RECORDING, true)
                Logger.i(TAG, "Sync", "Full sync received: recording=$isRecordingEnabled, silence=$isSilenceEnabled")
            } else {
                val key = intent.getStringExtra(PreferenceKeys.EXTRA_KEY) ?: return@registerSecureReceiver
                val value = intent.getBooleanExtra(PreferenceKeys.EXTRA_VALUE, true)
                when (key) {
                    PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT -> {
                        isSilenceEnabled = value
                        Logger.i(TAG, "Sync", "Setting [disable_voice_announcement] updated to $value")
                    }
                    PreferenceKeys.ENABLE_CALL_RECORDING -> {
                        isRecordingEnabled = value
                        Logger.i(TAG, "Sync", "Setting [enable_call_recording] updated to $value")
                    }
                }
            }
        }
        receiverRegistered = true
    }
}
