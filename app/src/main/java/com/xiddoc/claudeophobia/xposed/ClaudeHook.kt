package com.xiddoc.claudeophobia.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import com.xiddoc.claudeophobia.data.ClaudePrefs
import com.xiddoc.claudeophobia.data.ModuleStatus
import com.xiddoc.claudeophobia.data.UsageLog
import com.xiddoc.claudeophobia.data.UsageProvider
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
 *  - **Our own app** — we hook [ModuleStatus] so the app knows the module is
 *    actually active.
 *  - **The Claude app** — we read its session cookie + org id from *inside* the
 *    process (no root needed; the process owns the files) and courier them to our
 *    [UsageProvider]. We capture once when Claude's `Application` starts and again
 *    whenever a Claude activity resumes, so the credentials our app uses to fetch
 *    usage stay fresh. The app itself makes the `/usage` request.
 *
 * Everything is traced to logcat (tag `ClaudeUsage`) and the headline events to
 * the Xposed/LSPosed log, so a broken capture can be diagnosed step by step.
 */
class ClaudeHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        when (lpparam.packageName) {
            SELF_PACKAGE -> {
                XposedBridge.log("Claude-o-phobia: loaded into self ($SELF_PACKAGE) — marking module active")
                hookSelf(lpparam)
            }
            // System apps never carry a Claude cookie jar, so skip the noise.
            "android", "com.android.systemui" -> Unit
            else -> {
                XposedBridge.log("Claude-o-phobia: loaded into ${lpparam.packageName} — installing capture hooks")
                hookClaude(lpparam)
            }
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

    /** Capture on Claude `Application` start and on every activity resume. */
    private fun hookClaude(lpparam: LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        (param.args[0] as? Application)?.let { app ->
                            UsageLog.d("hook: Application.onCreate in ${lpparam.packageName}")
                            Thread { capture(app, force = true) }.start()
                        }
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        (param.thisObject as? Activity)?.applicationContext?.let { ctx ->
                            Thread { capture(ctx, force = false) }.start()
                        }
                    }
                },
            )
        }.onFailure { XposedBridge.log("Claude-o-phobia: hook on ${lpparam.packageName} failed: $it") }
    }

    /**
     * Reads Claude's cookie jar + selected org and publishes them to our app.
     * [force] bypasses the throttle (used on process start); resume-driven calls
     * are throttled and skipped when nothing changed.
     */
    private fun capture(context: Context, force: Boolean) {
        try {
            val dataDir = context.dataDir
            val cookies = ClaudePrefs.readCookies(dataDir)
            if (cookies.isEmpty()) {
                // No cookie jar here — almost certainly not the Claude app. Stay quiet.
                UsageLog.d("capture(): no cookie jar under ${dataDir.path}/shared_prefs — not Claude?")
                return
            }
            val sessionKey = cookies["sessionKey"]
            if (sessionKey.isNullOrBlank()) {
                UsageLog.w("capture(): cookie jar present but sessionKey blank — signed out?")
                return
            }

            val cookieHeader = ClaudePrefs.cookieHeader(cookies)
            val now = System.currentTimeMillis()
            val hash = cookieHeader.hashCode()
            if (!force && hash == lastCookieHash && now - lastPublishAt < THROTTLE_MS) {
                UsageLog.d("capture(): throttled (unchanged cookie, ${now - lastPublishAt}ms since last)")
                return
            }

            val orgId = ClaudePrefs.readSelectedOrgId(dataDir)
                ?: cookies["lastActiveOrg"]?.takeIf { it.isNotBlank() }
            UsageLog.d(
                "capture(): ${cookies.size} cookie(s), sessionKey ${UsageLog.redact(sessionKey)}, " +
                    "org ${UsageLog.redact(orgId)} — publishing (force=$force)"
            )

            publish(context, cookieHeader, orgId)
            lastCookieHash = hash
            lastPublishAt = now
        } catch (e: Throwable) {
            UsageLog.e("capture(): unexpected failure", e)
            XposedBridge.log("Claude-o-phobia: capture failed: $e")
        }
    }

    private fun publish(context: Context, cookieHeader: String, orgId: String?) {
        val extras = Bundle().apply {
            putString(UsageProvider.KEY_COOKIE, cookieHeader)
            orgId?.let { putString(UsageProvider.KEY_ORG, it) }
        }
        val result = runCatching {
            context.contentResolver.call(
                UsageProvider.CONTENT_URI,
                UsageProvider.METHOD_PUBLISH,
                null,
                extras,
            )
        }
        result.onFailure {
            UsageLog.e("publish(): contentResolver.call failed", it)
            XposedBridge.log("Claude-o-phobia: publish failed: $it")
        }.onSuccess {
            UsageLog.d("publish(): handed credentials to ${UsageProvider.AUTHORITY}")
        }
    }

    companion object {
        private const val SELF_PACKAGE = "com.xiddoc.claudeophobia"
        private const val THROTTLE_MS = 60_000L

        @Volatile private var lastPublishAt = 0L
        @Volatile private var lastCookieHash = 0
    }
}
