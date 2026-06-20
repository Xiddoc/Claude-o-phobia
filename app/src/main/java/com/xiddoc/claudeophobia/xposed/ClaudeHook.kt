package com.xiddoc.claudeophobia.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.xiddoc.claudeophobia.data.ClaudeClient
import com.xiddoc.claudeophobia.data.ClaudePrefs
import com.xiddoc.claudeophobia.data.UsageLog
import com.xiddoc.claudeophobia.data.UsageProvider
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * The LSPosed entry point (registered in `META-INF/xposed/java_init.list`).
 *
 * Built against the modern libxposed API (v102): we extend [XposedModule] and the
 * framework hands us an [XposedInterface] before dispatching the lifecycle
 * callbacks below. Hooks are installed with [hook]/`intercept`, an OkHttp-style
 * interceptor chain — call `chain.proceed()` to run the original, or return a value
 * without proceeding to short-circuit it.
 *
 * Two surfaces matter to us:
 *
 *  - **The Claude app** ([onPackageLoaded]) — we read its session cookie + org id
 *    from *inside* the process (no root needed; the process owns the files) and
 *    courier them to our [UsageProvider]. We capture once when Claude's
 *    `Application` starts and again whenever a Claude activity resumes, so the
 *    credentials our app uses to fetch usage stay fresh. The app itself makes the
 *    `/usage` request.
 *  - **The system server** ([onSystemServerStarting]) — Android 11+
 *    package-visibility filtering hides our (sideloaded) app from Claude, so the
 *    courier call above fails with `Unknown authority`. We can't opt into another
 *    app's visibility from our own manifest, so instead we hook the system server's
 *    package-visibility check and declare *our* package always-resolvable. This is
 *    the `forceQueryable` behaviour the platform reserves for system apps, applied
 *    to ourselves.
 *
 * Everything is traced to logcat (tag `ClaudeUsage`) and the headline events to
 * the Xposed/LSPosed log (via [log]), so a broken capture can be diagnosed step by
 * step.
 *
 * Note we deliberately do **not** hook our own app: LSPosed never loads a module
 * into its own process, so a self-hook can never run. Activation is inferred from
 * whether credentials actually arrive, not from a self-probe.
 */
class ClaudeHook : XposedModule() {

    /** Capture on Claude `Application` start and on every activity resume. */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Only the first (main) package of a process carries the app's data dir and
        // installs our process-wide capture hooks; secondary packages add nothing.
        if (!param.isFirstPackage) return
        when (param.packageName) {
            // The system UI never carries a Claude cookie jar — skip it. Our own
            // package is never loaded into us, so no self guard is needed.
            "com.android.systemui" -> Unit
            else -> {
                log(Log.INFO, TAG, "loaded into ${param.packageName} — installing capture hooks")
                hookClaude(param.packageName)
            }
        }
    }

    /**
     * Inside the system server, arm the hooks that make [SELF_PACKAGE] resolvable
     * regardless of the caller's `<queries>`. We wait for `systemReady` so the
     * `PackageManagerInternal` service is registered, then call
     * [installVisibilityBypass].
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "loaded into system_server — arming visibility bypass")
        hookSystem(param.classLoader)
    }

    private fun hookClaude(packageName: String) {
        runCatching {
            val callApplicationOnCreate = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java,
            )
            hook(callApplicationOnCreate).intercept { chain ->
                val result = chain.proceed()
                (chain.args.firstOrNull() as? Application)?.let { app ->
                    UsageLog.d("hook: Application.onCreate in $packageName")
                    Thread { capture(app, force = true) }.start()
                }
                result
            }
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            hook(onResume).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Activity)?.applicationContext?.let { ctx ->
                    Thread { capture(ctx, force = false) }.start()
                }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hook on $packageName failed: $it", it) }
    }

    private fun hookSystem(classLoader: ClassLoader) {
        runCatching {
            val pms = classLoader.loadClass("com.android.server.pm.PackageManagerService")
            val systemReady = pms.getDeclaredMethod("systemReady")
            hook(systemReady).intercept { chain ->
                val result = chain.proceed()
                installVisibilityBypass(classLoader)
                result
            }
            log(Log.INFO, TAG, "armed visibility bypass via PackageManagerService.systemReady")
        }.onFailure {
            UsageLog.e("hookSystem(): could not arm visibility bypass", it)
            log(Log.ERROR, TAG, "hookSystem failed: $it", it)
        }
    }

    /**
     * Makes [SELF_PACKAGE] resolvable from any process, regardless of the caller's
     * `<queries>`, by hooking the package-visibility filter in the system server.
     *
     * Every visibility decision (provider/authority resolution, `filterAppAccess`,
     * intent queries, …) funnels through `AppsFilter.shouldFilterApplication`, so
     * that's the one chokepoint we patch: whenever the *target* (or caller) is our
     * package we force the result to `false` ("don't hide"). We also patch
     * `PackageManagerInternal.filterAppAccess` directly as a cheap backstop. To
     * keep the system-wide hot path cheap we only reflect on args that look like a
     * package setting/state, and bail the moment we match.
     *
     * Class/method internals shifted across Android releases (the Android 14 PMS
     * refactor in particular), so we try several class names and report how many
     * methods we actually hooked to the LSPosed log — a `x0` there means the hook
     * didn't attach on this ROM and is the first thing to check.
     *
     * We reflect into `LocalServices`/`PackageManagerInternal` here, which lint
     * flags as blocked private API. That restriction is for ordinary apps; this
     * code only ever runs inside `system_server` (via [onSystemServerStarting]),
     * where the hidden-API denylist doesn't apply — so the check is suppressed.
     */
    @SuppressLint("BlockedPrivateApi")
    private fun installVisibilityBypass(classLoader: ClassLoader) {
        if (visibilityBypassInstalled) return

        var appsFilterHooks = 0
        for (name in APPS_FILTER_CLASSES) {
            runCatching {
                val cls = classLoader.loadClass(name)
                appsFilterHooks += hookAllMethods(cls, "shouldFilterApplication", shouldFilterHook)
            }
        }

        var filterAppAccessHooks = 0
        runCatching {
            val pmiClass = classLoader.loadClass("android.content.pm.PackageManagerInternal")
            val localServices = classLoader.loadClass("com.android.server.LocalServices")
            val getService = localServices.getDeclaredMethod("getService", Class::class.java)
            getService.invoke(null, pmiClass)?.let { pmi ->
                filterAppAccessHooks = hookAllMethods(pmi.javaClass, "filterAppAccess", filterAppAccessHook)
            }
        }.onFailure { UsageLog.w("installVisibilityBypass(): filterAppAccess hook skipped: $it") }

        visibilityBypassInstalled = appsFilterHooks > 0 || filterAppAccessHooks > 0
        val report = "shouldFilterApplication x$appsFilterHooks, filterAppAccess x$filterAppAccessHooks"
        UsageLog.d("installVisibilityBypass(): $report (installed=$visibilityBypassInstalled)")
        log(
            Log.INFO,
            TAG,
            if (visibilityBypassInstalled) {
                "visibility bypass active — $report; $SELF_PACKAGE resolvable from any process"
            } else {
                "visibility bypass FAILED to attach ($report) — check the LSPosed scope/version"
            },
        )
    }

    /** Hooks every overload of [name] on [cls] with [hooker]; returns the count attached. */
    private fun hookAllMethods(cls: Class<*>, name: String, hooker: XposedInterface.Hooker): Int {
        var count = 0
        for (method in cls.declaredMethods) {
            if (method.name != name) continue
            runCatching {
                hook(method).intercept(hooker)
                count++
            }
        }
        return count
    }

    /**
     * Forces `shouldFilterApplication` to `false` ("don't hide") whenever our
     * package is the caller or the target. Only objects that look like a package
     * setting/state are probed, so we avoid throwing on the snapshot/int args.
     */
    private val shouldFilterHook = XposedInterface.Hooker { chain ->
        for (arg in chain.args) {
            if (arg == null) continue
            val className = arg.javaClass.name
            if (!className.contains("PackageSetting") &&
                !className.contains("PackageState") &&
                !className.contains("Setting")
            ) continue
            val pkg = runCatching {
                arg.javaClass.getMethod("getPackageName").invoke(arg) as? String
            }.getOrNull()
            if (pkg == SELF_PACKAGE) return@Hooker false
        }
        chain.proceed()
    }

    /**
     * Backstop on `PackageManagerInternal.filterAppAccess`. Only the
     * `(String packageName, …)` overloads carry a target we can match; the
     * `(int uid, …)` ones leave args[0] non-String and fall through to the original.
     */
    private val filterAppAccessHook = XposedInterface.Hooker { chain ->
        if (chain.args.firstOrNull() as? String == SELF_PACKAGE) false else chain.proceed()
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

            // Capture the app's own version so our replayed request can present the
            // exact User-Agent Cloudflare's cf_clearance cookie is bound to.
            val client = ClaudeClient.forVersion(ClaudeClient.versionNameOf(context))
            UsageLog.d(
                "capture(): ${cookies.size} cookie(s), sessionKey ${UsageLog.redact(sessionKey)}, " +
                    "org ${UsageLog.redact(orgId)}, client ${client.clientVersion} — publishing (force=$force)"
            )

            publish(context, cookieHeader, orgId, client)
            lastCookieHash = hash
            lastPublishAt = now
        } catch (e: Throwable) {
            UsageLog.e("capture(): unexpected failure", e)
            log(Log.ERROR, TAG, "capture failed: $e", e)
        }
    }

    private fun publish(context: Context, cookieHeader: String, orgId: String?, client: ClaudeClient) {
        val extras = Bundle().apply {
            putString(UsageProvider.KEY_COOKIE, cookieHeader)
            orgId?.let { putString(UsageProvider.KEY_ORG, it) }
            putString(UsageProvider.KEY_USER_AGENT, client.userAgent)
            putString(UsageProvider.KEY_CLIENT_VERSION, client.clientVersion)
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
            log(Log.ERROR, TAG, "publish failed: $it", it)
        }.onSuccess {
            UsageLog.d("publish(): handed credentials to ${UsageProvider.AUTHORITY}")
        }
    }

    companion object {
        private const val TAG = "Claude-o-phobia"
        private const val SELF_PACKAGE = "com.xiddoc.claudeophobia"
        private const val THROTTLE_MS = 60_000L

        // The class that owns shouldFilterApplication moved across releases
        // (AppsFilter pre-14; split into AppsFilterImpl/AppsFilterBase on 14+).
        private val APPS_FILTER_CLASSES = arrayOf(
            "com.android.server.pm.AppsFilterImpl",
            "com.android.server.pm.AppsFilterBase",
            "com.android.server.pm.AppsFilter",
        )

        @Volatile private var lastPublishAt = 0L
        @Volatile private var lastCookieHash = 0
        @Volatile private var visibilityBypassInstalled = false
    }
}
