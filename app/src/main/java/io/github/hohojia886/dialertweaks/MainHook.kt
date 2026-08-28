package io.github.hohojia886.dialertweaks

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.hohojia886.dialertweaks.hooks.CallRecordingHook
import io.github.hohojia886.dialertweaks.utils.Logger

class MainHook : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        Logger.sync(this)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        
        if (param.packageName == "com.google.android.dialer" || param.packageName == "com.android.dialer") {
            Logger.i("Hook", "Started", "Applying hooks for [Dialer]")
            CallRecordingHook.hook(this, param.defaultClassLoader, param.packageName, param.applicationInfo.sourceDir)
        }
    }
}
