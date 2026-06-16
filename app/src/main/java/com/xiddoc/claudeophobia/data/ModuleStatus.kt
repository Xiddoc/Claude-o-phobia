package com.xiddoc.claudeophobia.data

import android.content.Context
import android.os.SystemClock

/**
 * Heuristics about the LSPosed module's state that the app can compute itself.
 *
 * We can't ask LSPosed whether the module is enabled, and we can't self-hook to
 * prove it (a module never appears inside its own scope, so a self-probe can
 * never load). The honest signal that the module is *working* is whether
 * credentials have actually arrived — see [AppSettings.hasCredentials]. The one
 * extra thing we can usefully detect is whether a reboot is still pending.
 */
object ModuleStatus {

    /**
     * True when this app was installed or updated *after* the last reboot.
     *
     * LSPosed only injects the module into Claude and the system server at
     * process start — i.e. on boot — so a freshly installed or updated module
     * isn't hooking anything until the device reboots. When that's the case and
     * we still have no credentials, the fix is simply "reboot".
     */
    fun rebootNeededSinceUpdate(context: Context): Boolean = try {
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val updatedMs = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime
        updatedMs > bootMs
    } catch (_: Throwable) {
        false
    }
}
