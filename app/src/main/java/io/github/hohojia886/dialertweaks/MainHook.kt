package io.github.hohojia886.dialertweaks

import android.os.Process
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.hohojia886.dialertweaks.hooks.PixelHook
import io.github.hohojia886.dialertweaks.hooks.CallRecordingHook
import io.github.hohojia886.dialertweaks.hooks.CallNotesHook
import io.github.hohojia886.dialertweaks.utils.Logger

private const val SYSTEMUI_PKG = "com.android.systemui"
private val DIALER_PKGS = setOf("com.google.android.dialer", "com.android.dialer")

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
        isRootSystemServer || packageName == SYSTEMUI_PKG || packageName in DIALER_PKGS
    override fun apply(module: XposedModule, classLoader: ClassLoader, param: PackageLoadedParam) {
        CallNotesHook.hook(module, classLoader, param.packageName)
    }
}

/**
 * Main entrance for the LSPosed module.
 */
class MainHook : XposedModule() {

    private var isSystemServerProcess = false

    private val allHooks: List<PixelHook> = listOf(
        CallRecordingEntry,
        CallNotesEntry
    )

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        isSystemServerProcess = param.isSystemServer || Process.myUid() == 1000
        Logger.sync(this)
        Logger.i(
            "Hook", "Started",
            "Module loaded (PID: ${Process.myPid()}, UID: ${Process.myUid()}, isSys: $isSystemServerProcess)"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        val pkgName = param.packageName
        val classLoader = param.defaultClassLoader
        
        // Synchronize logger state on every package load to ensure high-visibility
        Logger.sync(this)

        val isRootSystemServer = pkgName == "android" && isSystemServerProcess

        allHooks.forEach { hook ->
            if (!hook.matches(pkgName, isRootSystemServer)) return@forEach
            try {
                hook.apply(this, classLoader, param)
                Logger.i("Hook", "Applied", "[${hook.name}] successfully set up for $pkgName")
            } catch (t: Throwable) {
                // Isolated per hook
                Logger.e("Hook", "Error", "[${hook.name}] failed for $pkgName", t)
            }
        }
    }
}
