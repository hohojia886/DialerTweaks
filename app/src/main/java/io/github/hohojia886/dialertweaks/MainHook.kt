package io.github.hohojia886.dialertweaks

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.hohojia886.dialertweaks.hooks.PixelHook
import io.github.hohojia886.dialertweaks.hooks.CallRecordingHook
import io.github.hohojia886.dialertweaks.hooks.CallNotesHook
import io.github.hohojia886.dialertweaks.utils.Logger

private val DIALER_PKGS = setOf("com.google.android.dialer", "com.android.dialer")
private val SILENCE_PKGS = setOf(
    "com.google.android.dialer", 
    "com.android.dialer",
    "com.android.systemui"
)

private object CallRecordingEntry : PixelHook {
    override val name = "CallRecording"
    override fun matches(packageName: String, isRootSystemServer: Boolean) =
        packageName in DIALER_PKGS
    override fun apply(module: XposedModule, classLoader: ClassLoader, param: PackageLoadedParam) {
        CallRecordingHook.hookFull(module, classLoader, param.packageName, param.applicationInfo.sourceDir)
    }
}

private object CallNotesEntry : PixelHook {
    override val name = "CallNotes"
    override fun matches(packageName: String, isRootSystemServer: Boolean) =
        packageName in SILENCE_PKGS || isRootSystemServer
    override fun apply(module: XposedModule, classLoader: ClassLoader, param: PackageLoadedParam) {
        CallNotesHook.hook(module, classLoader, param.packageName)
    }
}

/**
 * Main entrance for the LSPosed module.
 */
class MainHook : XposedModule() {

    private val allHooks: List<PixelHook> = listOf(
        CallRecordingEntry,
        CallNotesEntry
    )

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        Logger.sync(this)
        Logger.i(
            "Hook", "Started",
            "Module loaded (PID: ${android.os.Process.myPid()}, UID: ${android.os.Process.myUid()})"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        
        // Synchronize logger state on every package load to ensure high-visibility
        Logger.sync(this)

        val isRootSystemServer = param.packageName == "android"

        allHooks.forEach { hook ->
            if (!hook.matches(param.packageName, isRootSystemServer)) return@forEach
            try {
                hook.apply(this, param.defaultClassLoader, param)
            } catch (t: Throwable) {
                // Isolated per hook
                Logger.e("Hook", "Error", "[${hook.name}] failed for ${param.packageName}", t)
            }
        }
    }
}
