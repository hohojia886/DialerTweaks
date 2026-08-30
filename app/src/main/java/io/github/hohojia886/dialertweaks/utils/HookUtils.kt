package io.github.hohojia886.dialertweaks.utils

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * LSPosed API 102 DSL Extensions.
 */

/**
 * Simple wrapper for a "Before" hook.
 */
inline fun XposedModule.hookBefore(
    method: Executable,
    crossinline block: (XposedInterface.Chain) -> Unit
): XposedInterface.HookHandle {
    return this.hook(method).intercept { chain ->
        block(chain)
        chain.proceed()
    }
}

/**
 * Simple wrapper for an "After" hook.
 */
inline fun XposedModule.hookAfter(
    method: Executable,
    crossinline block: (chain: XposedInterface.Chain, result: Any?) -> Unit
): XposedInterface.HookHandle {
    return this.hook(method).intercept { chain ->
        val res = chain.proceed()
        block(chain, res)
        res
    }
}
