package com.xiddoc.claudeophobia.data

/**
 * Lets the app tell whether its own LSPosed module is actually active.
 *
 * The trick is the classic Xposed self-check: [probe] returns `false` as
 * written, but when the module is enabled (and this app is in its scope) the
 * module hooks [probe] inside our process and forces it to return `true`. So a
 * `true` here means "LSPosed loaded our code into our own process", which is the
 * strongest signal we have that it's also hooking Claude.
 *
 * [probe] is invoked reflectively so R8 can't inline its constant body at the
 * call site and slip past the hook in release builds; [probe] is also kept by
 * `proguard-rules.pro`.
 */
object ModuleStatus {

    fun isActive(): Boolean = try {
        val m = ModuleStatus::class.java.getDeclaredMethod("probe").apply { isAccessible = true }
        m.invoke(this) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    /** Hooked by the module to return true. Never call directly — see above. */
    private fun probe(): Boolean = false
}
