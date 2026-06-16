package com.xiddoc.claudeophobia.xposed

import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import com.xiddoc.claudeophobia.data.ClaudeApi
import com.xiddoc.claudeophobia.data.ClaudePrefs
import com.xiddoc.claudeophobia.data.ModuleStatus
import com.xiddoc.claudeophobia.data.UsageProvider
import com.xiddoc.claudeophobia.data.UsageSnapshot
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * The LSPosed entry point (registered in `assets/xposed_init`).
 *
 * Two packages matter to us:
 *
 *  - **Our own app** — we hook [ModuleStatus.probe] to return `true`, which is
 *    how the app knows the module is actually active (see [ModuleStatus]).
 *  - **The Claude app** — once its `Application` is up, we read its cookie jar
 *    from *inside* the process (no root needed; the process owns the files),
 *    call the same `/usage` endpoint the app itself calls, and publish the
 *    resulting non-secret snapshot to our [UsageProvider]. The session cookie
 *    is used only for that one request and never leaves the Claude process.
 */
class ClaudeHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        when (lpparam.packageName) {
            SELF_PACKAGE -> hookSelf(lpparam)
            // System apps never carry a Claude cookie jar, so it's cheap to let
            // capture() decide; this keeps us working for renamed/cloned Claude
            // packages the user has put in the module's scope.
            "android" -> Unit
            else -> hookClaude(lpparam)
        }
    }

    /** Flip the in-app "module active?" probe to true inside our own process. */
    private fun hookSelf(lpparam: LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                ModuleStatus::class.java.name,
                lpparam.classLoader,
                "probe",
                XC_MethodReplacement.returnConstant(true),
            )
        }.onFailure { XposedBridge.log("Claude-o-phobia: self-hook failed: $it") }
    }

    /** Capture usage once the (suspected) Claude app has finished starting up. */
    private fun hookClaude(lpparam: LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        (param.args[0] as? Application)?.let { app ->
                            // callApplicationOnCreate runs on the main thread; do
                            // the disk + network work off it.
                            Thread { capture(app) }.start()
                        }
                    }
                },
            )
        }.onFailure { XposedBridge.log("Claude-o-phobia: hook on ${lpparam.packageName} failed: $it") }
    }

    private fun capture(app: Application) {
        val cookies = ClaudePrefs.readCookies(app.dataDir)
        if (cookies.isEmpty()) {
            // No cookie jar here — almost certainly not the Claude app. Stay quiet.
            return
        }
        val sessionKey = cookies["sessionKey"]
        if (sessionKey.isNullOrBlank()) {
            publishError(app, "Found Claude's cookie jar but no session — are you signed in?")
            return
        }

        val cookieHeader = ClaudePrefs.cookieHeader(cookies)
        val orgId = ClaudePrefs.readSelectedOrgId(app.dataDir)
            ?: cookies["lastActiveOrg"]?.takeIf { it.isNotBlank() }
            ?: ClaudeApi.fetchFirstOrgId(cookieHeader)
        if (orgId == null) {
            publishError(app, "Couldn't determine your Claude organization id.")
            return
        }

        try {
            val snapshot = ClaudeApi.fetchUsage(orgId, cookieHeader)
            publishUsage(app, snapshot)
            XposedBridge.log(
                "Claude-o-phobia: published weekly=${snapshot.weeklyUtilizationPercent}%",
            )
        } catch (e: ClaudeApi.HttpException) {
            publishError(app, e.message ?: "Claude returned HTTP ${e.code}.")
        } catch (e: Exception) {
            publishError(app, "Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun publishUsage(app: Application, snapshot: UsageSnapshot) {
        val extras = Bundle().apply {
            putBoolean(UsageProvider.KEY_OK, true)
            snapshot.weeklyUtilizationPercent?.let { putDouble(UsageProvider.KEY_WEEKLY_PCT, it) }
            snapshot.weeklyResetEpochMs?.let { putLong(UsageProvider.KEY_WEEKLY_RESET, it) }
            snapshot.fiveHourUtilizationPercent?.let { putDouble(UsageProvider.KEY_FIVE_PCT, it) }
            snapshot.fiveHourResetEpochMs?.let { putLong(UsageProvider.KEY_FIVE_RESET, it) }
            snapshot.sourceLabel?.let { putString(UsageProvider.KEY_SOURCE, it) }
        }
        publish(app, extras)
    }

    private fun publishError(app: Application, detail: String) {
        val extras = Bundle().apply {
            putBoolean(UsageProvider.KEY_OK, false)
            putString(UsageProvider.KEY_ERROR, detail)
        }
        publish(app, extras)
    }

    private fun publish(app: Application, extras: Bundle) {
        runCatching {
            app.contentResolver.call(
                UsageProvider.CONTENT_URI,
                UsageProvider.METHOD_PUBLISH,
                null,
                extras,
            )
        }.onFailure { XposedBridge.log("Claude-o-phobia: publish failed: $it") }
    }

    companion object {
        private const val SELF_PACKAGE = "com.xiddoc.claudeophobia"
    }
}
