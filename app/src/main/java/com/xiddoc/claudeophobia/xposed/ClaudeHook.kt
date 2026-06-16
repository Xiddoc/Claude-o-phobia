package com.xiddoc.claudeophobia.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Binder
import android.os.Bundle
import com.xiddoc.claudeophobia.data.ClaudePrefs
import com.xiddoc.claudeophobia.data.UsageLog
import com.xiddoc.claudeophobia.data.UsageProvider
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * The LSPosed entry point (registered in `assets/xposed_init`).
 *
 * Two packages matter to us:
 *
 *  - **The Claude app** — we read its session cookie + org id from *inside* the
 *    process (no root needed; the process owns the files) and courier them to our
 *    [UsageProvider]. We capture once when Claude's `Application` starts and again
 *    whenever a Claude activity resumes, so the credentials our app uses to fetch
 *    usage stay fresh. The app itself makes the `/usage` request.
 *  - **The system server** (`android`) — Android 11+ package-visibility filtering
 *    hides our (sideloaded) app from Claude, so the courier call above fails with
 *    `Unknown authority`. We can't opt into another app's visibility from our own
 *    manifest, so instead we hook the system server's package-visibility check and
 *    declare *our* package always-resolvable. This is the `forceQueryable`
 *    behaviour the platform reserves for system apps, applied to ourselves.
 *
 * Everything is traced to logcat (tag `ClaudeUsage`) and the headline events to
 * the Xposed/LSPosed log, so a broken capture can be diagnosed step by step.
 *
 * Note we deliberately do **not** hook our own app: LSPosed doesn't list a module
 * inside its own scope, so a self-hook can never load. Activation is inferred from
 * whether credentials actually arrive, not from a self-probe.
 */
class ClaudeHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        when (lpparam.packageName) {
            // The system server: make our package visible to every process so the
            // courier ContentProvider call from inside Claude can resolve us.
            "android" -> {
                XposedBridge.log("Claude-o-phobia: loaded into system_server — arming visibility bypass")
                hookSystem(lpparam)
            }
            // Our own app can't host the module (not in its own scope) and the
            // system UI never carries a Claude cookie jar — skip both.
            SELF_PACKAGE, "com.android.systemui" -> Unit
            else -> {
                XposedBridge.log("Claude-o-phobia: loaded into ${lpparam.packageName} — installing capture hooks")
                hookClaude(lpparam)
            }
        }
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
     * Inside the system server, arm the hooks that make [SELF_PACKAGE] resolvable
     * regardless of the caller's `<queries>`. We wait for `systemReady` so the
     * `PackageManagerInternal` service is registered, then call
     * [installVisibilityBypass].
     */
    private fun hookSystem(lpparam: LoadPackageParam) {
        runCatching {
            val pms = XposedHelpers.findClass(
                "com.android.server.pm.PackageManagerService",
                lpparam.classLoader,
            )
            XposedHelpers.findAndHookMethod(
                pms,
                "systemReady",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        installVisibilityBypass(lpparam.classLoader)
                    }
                },
            )
            XposedBridge.log("Claude-o-phobia: armed visibility bypass via PackageManagerService.systemReady")
        }.onFailure {
            UsageLog.e("hookSystem(): could not arm visibility bypass", it)
            XposedBridge.log("Claude-o-phobia: hookSystem failed: $it")
        }
    }

    /**
     * Opens the two package-visibility gates a cross-app `ContentResolver.call`
     * passes through in `getContentProviderImpl`, scoped tightly to *our* app:
     *
     *  1. **Authority resolution** — `PackageManagerService.resolveContentProvider`
     *     filters by the caller's uid and can return null (→ "Unknown authority")
     *     before any later check. For our authority we run it with the calling
     *     identity cleared, so it resolves as the system would.
     *  2. **Access filter** — `PackageManagerInternal.filterAppAccess` returns
     *     `true` to *hide* a target package from a caller. For our package we force
     *     `false` so the resolved provider isn't filtered back out.
     *
     * Both hooks only react to our own authority/package, so the hot system-wide
     * visibility path is left untouched.
     */
    private fun installVisibilityBypass(classLoader: ClassLoader) {
        if (visibilityBypassInstalled) return
        runCatching {
            // Gate 1: let our authority resolve regardless of the caller's uid.
            val pms = XposedHelpers.findClass(
                "com.android.server.pm.PackageManagerService",
                classLoader,
            )
            val resolveHooks = XposedBridge.hookAllMethods(
                pms,
                "resolveContentProvider",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (param.args.firstOrNull() == SELF_AUTHORITY) {
                            param.setObjectExtra(EXTRA_ID_TOKEN, Binder.clearCallingIdentity())
                        }
                    }

                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        (param.getObjectExtra(EXTRA_ID_TOKEN) as? Long)?.let {
                            Binder.restoreCallingIdentity(it)
                        }
                    }
                },
            )

            // Gate 2: don't filter our package back out of the caller's view.
            val pmiClass = XposedHelpers.findClass(
                "android.content.pm.PackageManagerInternal",
                classLoader,
            )
            val localServices = XposedHelpers.findClass(
                "com.android.server.LocalServices",
                classLoader,
            )
            val pmi = XposedHelpers.callStaticMethod(localServices, "getService", pmiClass)
                ?: run {
                    UsageLog.w("installVisibilityBypass(): PackageManagerInternal not registered yet")
                    return
                }
            val filterHooks = XposedBridge.hookAllMethods(
                pmi.javaClass,
                "filterAppAccess",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // Only the (String packageName, …) overloads carry a target
                        // we can match; the (int uid, …) ones leave args[0] non-String.
                        if (param.args.firstOrNull() as? String == SELF_PACKAGE) {
                            param.result = false
                        }
                    }
                },
            )

            visibilityBypassInstalled = true
            UsageLog.d(
                "installVisibilityBypass(): hooked resolveContentProvider x${resolveHooks.size}, " +
                    "filterAppAccess x${filterHooks.size} on ${pmi.javaClass.name}"
            )
            XposedBridge.log(
                "Claude-o-phobia: visibility bypass active — $SELF_PACKAGE is now resolvable from any process"
            )
        }.onFailure {
            UsageLog.e("installVisibilityBypass(): failed", it)
            XposedBridge.log("Claude-o-phobia: installVisibilityBypass failed: $it")
        }
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
        private const val SELF_AUTHORITY = "$SELF_PACKAGE.usage"
        private const val EXTRA_ID_TOKEN = "claudeophobia_id_token"
        private const val THROTTLE_MS = 60_000L

        @Volatile private var lastPublishAt = 0L
        @Volatile private var lastCookieHash = 0
        @Volatile private var visibilityBypassInstalled = false
    }
}
