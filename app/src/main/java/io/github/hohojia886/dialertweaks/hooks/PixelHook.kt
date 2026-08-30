package io.github.hohojia886.dialertweaks.hooks

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Common contract every feature hook implements so MainHook can dispatch to it
 * without needing a hand-maintained `when` block per package name.
 */
interface PixelHook {
    /** Short human-readable name used in log lines, e.g. "CallRecording". */
    val name: String

    /**
     * Whether this hook should run for the process currently being loaded.
     */
    fun matches(packageName: String, isRootSystemServer: Boolean): Boolean

    /** Actually apply the hook. Exceptions are caught by the caller (MainHook). */
    fun apply(module: XposedModule, classLoader: ClassLoader, param: PackageLoadedParam)
}
