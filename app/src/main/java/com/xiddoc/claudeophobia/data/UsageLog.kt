package com.xiddoc.claudeophobia.data

import android.util.Log

/**
 * Centralised, intentionally-verbose logging for the "read live Claude usage"
 * sequence the LSPosed module runs. Filter logcat by the [TAG] to watch the flow:
 *
 * ```
 * adb logcat -s ClaudeUsage
 * ```
 *
 * Privacy: we log the *shape* of the sequence — which branch ran, how many
 * cookies were found, HTTP status codes, body sizes — but never the secret
 * values themselves. Anything sensitive (the `sessionKey`, raw cookie jar,
 * the full org id) is passed through [redact] first, so a log line can confirm
 * "a 148-char sessionKey was present" without ever spelling it out.
 */
internal object UsageLog {
    const val TAG = "ClaudeUsage"

    fun d(message: String) = Log.d(TAG, message).let { }

    fun w(message: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
    }

    /**
     * Masks a secret so its presence and length are observable but its value
     * isn't. e.g. `"sk-ant-abc…yz (148 chars)"`.
     */
    fun redact(value: String?): String {
        if (value == null) return "<null>"
        if (value.isEmpty()) return "<empty>"
        val n = value.length
        return if (n <= 8) "***($n chars)" else "${value.take(4)}…${value.takeLast(2)} ($n chars)"
    }
}
