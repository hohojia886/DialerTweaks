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

@SuppressLint("DiscouragedApi", "SoonBlockedPrivateApi")
object CallRecordingHook {

    private const val TAG = "CallRec"
    private const val CACHE_FILE = "call_rec_v1.cache"

    private val DEX_KEYWORDS = listOf(
        "canRecordCall", "Crosby", "GeoFence", "isCallRecordingCountry"
    )

    @Volatile private var isRecordingEnabled = true
    @Volatile private var isSilenceEnabled = true
    private var receiverRegistered = false
    
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
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader, packageName: String, sourceDir: String?) {
        Logger.i(TAG, "Init", "Initializing CallRecording module")
        syncState(module)
        val moduleUid = module.getModuleApplicationInfo().uid

        try {
            val tm = TelephonyManager::class.java
            val isoInterceptor: (XposedInterface.Chain) -> Any? = { chain ->
                if (isRecordingEnabled) "us" else chain.proceed()
            }

            runCatching {
                module.hook(tm.getDeclaredMethod("getSimCountryIso")).intercept(isoInterceptor)
                module.hook(tm.getDeclaredMethod("getNetworkCountryIso")).intercept(isoInterceptor)
                module.hook(tm.getDeclaredMethod("getSimCountryIso", Int::class.javaPrimitiveType)).intercept(isoInterceptor)
                module.hook(tm.getDeclaredMethod("getNetworkCountryIso", Int::class.javaPrimitiveType)).intercept(isoInterceptor)
            }

            module.hook(classLoader.loadClass("android.app.Application").getDeclaredMethod("onCreate")).intercept { chain ->
                val app = chain.thisObject as? Context
                if (app != null) {
                    registerReceiver(app, moduleUid)
                    syncState(module)
                }
                chain.proceed()
            }

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
            }

            hookTtsHooks(module)
            
            if (sourceDir != null) {
                applyDexHooks(module, classLoader, packageName, sourceDir)
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "Error", "Framework hook application failed", e)
        }
    }

    private fun applyDexHooks(module: XposedModule, classLoader: ClassLoader, packageName: String, sourceDir: String) {
        val cacheFile = File(module.getModuleApplicationInfo().dataDir, CACHE_FILE)
        val currentVersion = runCatching {
             IpcManager.getSystemContext(classLoader)?.packageManager?.getPackageInfo(packageName, 0)?.longVersionCode ?: 0L
        }.getOrDefault(0L)

        if (loadFromCache(module, classLoader, cacheFile, currentVersion)) return

        Thread {
            runCatching {
                val moduleLibDir = module.getModuleApplicationInfo().nativeLibraryDir
                val dexKitLib = File(moduleLibDir, "libdexkit.so")
                if (dexKitLib.exists()) { @Suppress("UnsafeDynamicallyLoadedCode") System.load(dexKitLib.absolutePath) }
                else { runCatching { System.loadLibrary("dexkit") } }

                val foundMethods = mutableListOf<String>()
                foundMethods.add("VERSION|$currentVersion")

                DexKitBridge.create(sourceDir).use { bridge ->
                    DEX_KEYWORDS.forEach { word ->
                        val candidates = bridge.findMethod { matcher { usingStrings(word); returnType = "boolean" } }
                        candidates.forEach { data ->
                            runCatching {
                                val method = data.getMethodInstance(classLoader)
                                module.hook(method).intercept { if (isRecordingEnabled) true else it.proceed() }
                                foundMethods.add("FLAG|$word|${data.className}#${data.methodName}")
                            }
                        }
                    }
                    val localeCands = bridge.findMethod { matcher { usingStrings("getSupportedLocaleFromCountryCode"); returnType = "java.util.Locale" } }
                    localeCands.firstOrNull()?.let { data ->
                        runCatching {
                            val m = data.getMethodInstance(classLoader)
                            module.hook(m).intercept { if (isRecordingEnabled) Locale.US else it.proceed() }
                            foundMethods.add("LOCALE|${data.className}#${data.methodName}")
                        }
                    }
                }
                if (foundMethods.size > 1) cacheFile.writeText(foundMethods.joinToString("\n"))
            }
        }.start()
    }

    private fun loadFromCache(module: XposedModule, cl: ClassLoader, cacheFile: File, currentVersion: Long): Boolean {
        if (!cacheFile.exists()) return false
        return runCatching {
            val lines = cacheFile.readLines()
            if (lines.isEmpty() || !lines[0].startsWith("VERSION|$currentVersion")) return false
            lines.drop(1).forEach { line ->
                val parts = line.split("|")
                val mParts = parts.last().split("#")
                val clazz = cl.loadClass(mParts[0])
                val method = clazz.declaredMethods.find { it.name == mParts[1] } ?: return@forEach
                module.hook(method).intercept { chain ->
                    when (parts[0]) {
                        "LOCALE" -> if (isRecordingEnabled) Locale.US else chain.proceed()
                        "FLAG" -> if (isRecordingEnabled) true else chain.proceed()
                        else -> chain.proceed()
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun hookTtsHooks(module: XposedModule) {
        val ctorInterceptor: (XposedInterface.Chain) -> Any? = { chain ->
            val listener = chain.args[1] as? TextToSpeech.OnInitListener
            if (isRecordingEnabled && isSilenceEnabled && listener != null) {
                listener.onInit(TextToSpeech.SUCCESS)
            }
            chain.proceed()
        }

        runCatching {
            val c1 = TextToSpeech::class.java.getDeclaredConstructor(Context::class.java, TextToSpeech.OnInitListener::class.java)
            module.hook(c1).intercept(ctorInterceptor)
            val c2 = TextToSpeech::class.java.getDeclaredConstructor(Context::class.java, TextToSpeech.OnInitListener::class.java, String::class.java)
            module.hook(c2).intercept(ctorInterceptor)
        }

        runCatching {
            val m = TextToSpeech::class.java.getDeclaredMethod("isLanguageAvailable", Locale::class.java)
            module.hook(m).intercept { TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE }
        }

        runCatching {
            val m = TextToSpeech::class.java.getDeclaredMethod("setOnUtteranceProgressListener", UtteranceProgressListener::class.java)
            module.hook(m).intercept { chain ->
                lastListener = (chain.args[0] as? UtteranceProgressListener)?.let { WeakReference(it) }
                chain.proceed()
            }
        }

        val speakInterceptor: (XposedInterface.Chain) -> Any? = { chain ->
            if (isRecordingEnabled && isSilenceEnabled) {
                val utteranceId = chain.args[3] as? String
                val targetFile = if (chain.args.size >= 3 && chain.args[2] is File) chain.args[2] as File else null
                if (targetFile != null) {
                    runCatching { targetFile.outputStream().use { it.write(buildSilentWav()) } }
                }
                lastListener?.get()?.let { listener ->
                    utteranceId?.let { id ->
                        listener.onStart(id)
                        listener.onDone(id)
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
            val mSynth = TextToSpeech::class.java.getDeclaredMethod("synthesizeToFile", CharSequence::class.java, Bundle::class.java, File::class.java, String::class.java)
            module.hook(mSynth).intercept(speakInterceptor)
        }
    }

    private fun registerReceiver(context: Context, moduleUid: Int) {
        if (receiverRegistered) return
        IpcManager.registerSecureReceiver(context, moduleUid) { intent ->
            val action = intent.action ?: return@registerSecureReceiver
            if (action == IpcManager.ACTION_SETTINGS_SYNC) {
                isSilenceEnabled = intent.getBooleanExtra(PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT, true)
                isRecordingEnabled = intent.getBooleanExtra(PreferenceKeys.ENABLE_CALL_RECORDING, true)
            } else {
                val key = intent.getStringExtra(PreferenceKeys.EXTRA_KEY) ?: return@registerSecureReceiver
                val value = intent.getBooleanExtra(PreferenceKeys.EXTRA_VALUE, true)
                when (key) {
                    PreferenceKeys.DISABLE_VOICE_ANNOUNCEMENT -> isSilenceEnabled = value
                    PreferenceKeys.ENABLE_CALL_RECORDING -> isRecordingEnabled = value
                }
            }
        }
        receiverRegistered = true
    }
}
