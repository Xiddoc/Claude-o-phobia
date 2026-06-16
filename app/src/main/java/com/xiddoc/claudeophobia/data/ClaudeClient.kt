package com.xiddoc.claudeophobia.data

import android.content.Context
import android.os.Build

/**
 * The identity our replayed `/usage` request presents to claude.ai.
 *
 * We forward the Claude app's *own* session cookies, and Cloudflare binds the
 * `cf_clearance` cookie in that jar to the exact `User-Agent` that obtained it —
 * the Claude **Android app**, not a browser. Replaying those cookies under a
 * Chrome user-agent (what we used to do) makes Cloudflare re-challenge and return
 * a 403 "Just a moment…" page. So we impersonate the app instead: same
 * `User-Agent` and the same `Anthropic-Client-*` headers it sends.
 *
 * The wire user-agent the app uses looks like:
 *
 * ```
 * claude-android/1.260604.30 (Android 14; Pixel 7 Build/AP4A.250105.002)
 * ```
 *
 * i.e. `claude-android/<versionName> (Android <release>; <model> Build/<id>)`,
 * where `<versionName>` is the Claude app's own version. The module captures it
 * from inside the Claude process (see [com.xiddoc.claudeophobia.xposed.ClaudeHook])
 * and couriers it across; [forDevice] reproduces the exact same string locally as
 * a fallback for before the first capture.
 */
data class ClaudeClient(
    val userAgent: String,
    val clientVersion: String,
) {
    companion object {
        /** Platform value the Android app sends (the web client sends `web_claude_ai`). */
        const val CLIENT_PLATFORM = "android"

        /**
         * The Claude app version to assume before the module has couriered the real
         * one. Only a bridge: the very flow that captures cookies also captures the
         * live version, so this is replaced the first time the user opens Claude.
         */
        const val FALLBACK_CLIENT_VERSION = "1.260604.30"

        /** Builds the app's wire user-agent for [clientVersion] on this device. */
        fun userAgentFor(clientVersion: String): String =
            "claude-android/$clientVersion (Android ${Build.VERSION.RELEASE}; " +
                "${Build.MODEL} Build/${Build.ID})"

        /**
         * The identity to use for a given Claude app [versionName]. [Build] reflects
         * the physical device, which is identical whether we read it from inside the
         * Claude process or our own — so this reproduces the app's wire user-agent
         * exactly.
         */
        fun forVersion(versionName: String?): ClaudeClient {
            val version = versionName?.takeIf { it.isNotBlank() } ?: FALLBACK_CLIENT_VERSION
            return ClaudeClient(userAgentFor(version), version)
        }

        /** Reads the Claude app's own version name from inside its process. */
        fun versionNameOf(context: Context): String? = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
}
