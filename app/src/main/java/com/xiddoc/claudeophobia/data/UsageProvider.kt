package com.xiddoc.claudeophobia.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.xiddoc.claudeophobia.widget.UsageWidget
import kotlinx.coroutines.runBlocking

/**
 * The bridge the LSPosed module publishes into.
 *
 * The module runs inside the Claude process (a different uid), reads the cookie
 * jar, calls `claude.ai/.../usage`, and hands us back the resulting — non-secret
 * — [UsageSnapshot] via [call]. Hosting that here, in our own process, means the
 * snapshot lands straight in our [SettingsRepository]; the session cookie never
 * crosses the process boundary.
 *
 * The provider is exported (so Claude's uid can reach it) but only understands
 * the one [METHOD_PUBLISH] call and ignores everything else.
 */
class UsageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_PUBLISH || extras == null) return null
        val ctx = context ?: return null
        val repo = SettingsRepository(ctx)

        if (extras.getBoolean(KEY_OK, false)) {
            val snapshot = UsageSnapshot(
                weeklyUtilizationPercent = extras.doubleOrNull(KEY_WEEKLY_PCT),
                weeklyResetEpochMs = extras.longOrNull(KEY_WEEKLY_RESET),
                fiveHourUtilizationPercent = extras.doubleOrNull(KEY_FIVE_PCT),
                fiveHourResetEpochMs = extras.longOrNull(KEY_FIVE_RESET),
                sourceLabel = extras.getString(KEY_SOURCE) ?: "LSPosed",
            )
            runBlocking { repo.publishUsage(snapshot) }
        } else {
            val detail = extras.getString(KEY_ERROR)
                ?: "The module couldn't read your usage from Claude."
            runBlocking { repo.publishError(detail) }
        }

        // Reflect the fresh figure on any placed widget immediately.
        UsageWidget.refresh(ctx)
        return null
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

        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_WEEKLY_PCT = "weekly_pct"
        const val KEY_WEEKLY_RESET = "weekly_reset_ms"
        const val KEY_FIVE_PCT = "five_pct"
        const val KEY_FIVE_RESET = "five_reset_ms"
        const val KEY_SOURCE = "source"
    }
}

private fun Bundle.doubleOrNull(key: String): Double? =
    if (containsKey(key)) getDouble(key) else null

private fun Bundle.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null
