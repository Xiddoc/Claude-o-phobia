package com.xiddoc.claudeophobia.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.xiddoc.claudeophobia.widget.UsageWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The bridge the LSPosed module publishes into.
 *
 * The module runs inside the Claude process (a different uid), reads Claude's
 * session cookie + org id from the app's own storage, and hands them to us via
 * [call]. Hosting that here, in our own process, means the credentials land
 * straight in our [SettingsRepository] — and, because the module publishes
 * whenever Claude starts or comes to the foreground, this is also the moment we
 * kick off a fresh `/usage` fetch so the cached figure + widget update without
 * the user ever opening *our* app.
 *
 * The provider is exported (so Claude's uid can reach it) but only understands
 * the one [METHOD_PUBLISH] call and ignores everything else.
 */
class UsageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        UsageLog.d("UsageProvider.call(): method='$method' from uid=${android.os.Binder.getCallingUid()}")
        if (method != METHOD_PUBLISH || extras == null) {
            UsageLog.w("UsageProvider.call(): ignoring unknown method='$method' (extras=${extras != null})")
            return null
        }
        val ctx = context ?: run {
            UsageLog.w("UsageProvider.call(): no context — dropping publish")
            return null
        }

        val cookieHeader = extras.getString(KEY_COOKIE)
        if (cookieHeader.isNullOrBlank()) {
            UsageLog.w("UsageProvider.call(): publish carried no cookie header — dropping")
            return null
        }
        val orgId = extras.getString(KEY_ORG)
        val userAgent = extras.getString(KEY_USER_AGENT)
        val clientVersion = extras.getString(KEY_CLIENT_VERSION)
        UsageLog.d(
            "UsageProvider.call(): storing credentials — cookie ${cookieHeader.length} chars, " +
                "org ${UsageLog.redact(orgId)}, client version ${clientVersion ?: "(none)"}"
        )

        runBlocking {
            SettingsRepository(ctx).publishCredentials(cookieHeader, orgId, userAgent, clientVersion)
        }
        UsageLog.d("UsageProvider.call(): credentials persisted")

        // Don't block the binder call from Claude on the network; refresh detached.
        Thread { refreshFromFreshCredentials(ctx) }.start()
        return null
    }

    /**
     * Fetches usage with the just-stored credentials and updates the cached
     * figure + widget. Runs even when our own UI is closed, so simply opening
     * Claude keeps the widget current. The module is the caller, so it's active.
     */
    private fun refreshFromFreshCredentials(ctx: Context) {
        runCatching {
            runBlocking {
                val settings = SettingsRepository(ctx).settings.first()
                if (!settings.liveUsageEnabled) {
                    UsageLog.d("UsageProvider: live usage disabled — skipping auto-refresh")
                    return@runBlocking
                }
                // Credentials were just published, so the read goes straight to
                // the network — the reboot hint is irrelevant here.
                when (val result = LiveUsageReader().read(settings, rebootNeeded = false)) {
                    is UsageResult.Found -> {
                        SettingsRepository(ctx).cacheLiveUsage(result.snapshot.weeklyUtilizationPercent)
                        UsageWidget.refresh(ctx)
                        UsageLog.d("UsageProvider: auto-refresh cached ${result.snapshot.weeklyUtilizationPercent}%")
                    }
                    else -> UsageLog.d("UsageProvider: auto-refresh produced ${result.javaClass.simpleName}")
                }
            }
        }.onFailure { UsageLog.e("UsageProvider: auto-refresh failed", it) }
    }

    // --- Unused CRUD surface; this provider only speaks call(). ---

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** Must match the `android:authorities` in the manifest. */
        const val AUTHORITY = "com.xiddoc.claudeophobia.usage"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_PUBLISH = "publish"

        const val KEY_COOKIE = "cookie_header"
        const val KEY_ORG = "org_id"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_CLIENT_VERSION = "client_version"
    }
}
