package com.xiddoc.claudeophobia.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Talks to the same endpoint the Claude web/app client uses for usage:
 *
 *   GET https://claude.ai/api/organizations/{orgId}/usage
 *
 * Authentication is whatever cookies we forward (most importantly the
 * `sessionKey`), which the LSPosed module reads from the Claude app's own cookie
 * jar inside its process — this class never stores, logs, or hard-codes any of
 * them.
 *
 * Example response shape (no secrets in it):
 * {
 *   "five_hour": { "utilization": 0.0, "resets_at": null },
 *   "seven_day": { "utilization": 15.0, "resets_at": "2026-06-18T05:00:00.119296+00:00" }
 * }
 * `utilization` is already a percentage (0..100).
 */
object ClaudeApi {

    private const val BASE = "https://claude.ai"

    fun fetchUsage(orgId: String, cookieHeader: String, client: ClaudeClient): UsageSnapshot {
        UsageLog.d("fetchUsage(): GET /api/organizations/{org}/usage for org ${UsageLog.redact(orgId)}")
        val body = httpGet("$BASE/api/organizations/$orgId/usage", cookieHeader, client)
        return parseUsage(body)
    }

    /** Thrown for non-2xx responses so callers can show a precise reason. */
    class HttpException(val code: Int, message: String) : IOException(message)

    /** Best-effort discovery of an organization id when no cookie supplies one. */
    fun fetchFirstOrgId(cookieHeader: String, client: ClaudeClient): String? {
        UsageLog.d("fetchFirstOrgId(): GET /api/organizations to discover an org id")
        val body = runCatching { httpGet("$BASE/api/organizations", cookieHeader, client) }
            .onFailure { UsageLog.w("fetchFirstOrgId(): request failed", it) }
            .getOrNull() ?: return null
        return runCatching {
            val arr = JSONArray(body)
            UsageLog.d("fetchFirstOrgId(): organizations payload listed ${arr.length()} org(s)")
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("uuid")?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            null
        }.onFailure { UsageLog.w("fetchFirstOrgId(): could not parse organizations payload", it) }
            .getOrNull()
    }

    /** Parses the usage JSON into a snapshot. Visible for testing. */
    fun parseUsage(body: String): UsageSnapshot {
        val root = JSONObject(body)
        val sevenDay = root.optJSONObject("seven_day")
        val fiveHour = root.optJSONObject("five_hour")
        return UsageSnapshot(
            weeklyUtilizationPercent = sevenDay?.optDoubleOrNull("utilization"),
            weeklyResetEpochMs = sevenDay?.optString("resets_at")?.let(::parseEpochMillis),
            fiveHourUtilizationPercent = fiveHour?.optDoubleOrNull("utilization"),
            fiveHourResetEpochMs = fiveHour?.optString("resets_at")?.let(::parseEpochMillis),
            sourceLabel = "claude.ai/api",
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeIf { !it.isNaN() }
    }

    private fun parseEpochMillis(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value == "null") return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            value.toLongOrNull()?.let { if (it > 100_000_000_000L) it else it * 1000L }
        }
    }

    private fun httpGet(urlStr: String, cookieHeader: String, client: ClaudeClient): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Cookie", cookieHeader)
            // Impersonate the Claude Android app, not a browser: Cloudflare binds
            // the forwarded cf_clearance cookie to the exact User-Agent that earned
            // it, and that jar belongs to the app. A mismatched UA -> 403 challenge.
            setRequestProperty("User-Agent", client.userAgent)
            setRequestProperty("Accept", "*/*")
            // Avoid zstd/br bodies HttpURLConnection can't decode.
            setRequestProperty("Accept-Encoding", "identity")
            // The same client headers the app puts on every request.
            setRequestProperty("Anthropic-Client-Platform", ClaudeClient.CLIENT_PLATFORM)
            setRequestProperty("Anthropic-Client-Version", client.clientVersion)
            deviceIdFrom(cookieHeader)?.let { setRequestProperty("Anthropic-Device-ID", it) }
        }
        try {
            UsageLog.d("httpGet(): GET $urlStr (Cookie header ${cookieHeader.length} chars)")
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                UsageLog.d("httpGet(): $urlStr -> HTTP $code, ${body.length} byte body")
                return body
            }
            val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }
                ?.take(180)
                ?.replace(Regex("\\s+"), " ")
                .orEmpty()
            UsageLog.w("httpGet(): $urlStr -> HTTP $code; detail: $detail")
            throw HttpException(code, reasonFor(code, detail))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pulls the `anthropic-device-id` cookie out of a Cookie header so we can echo
     * it back in the `Anthropic-Device-ID` request header, exactly as the app does.
     * Returns null when the cookie isn't present. Visible for testing.
     */
    fun deviceIdFrom(cookieHeader: String): String? =
        cookieHeader.splitToSequence(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("anthropic-device-id=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private fun reasonFor(code: Int, detail: String): String = when (code) {
        401, 403 -> "Claude rejected the request (HTTP $code). The session cookie may " +
            "be expired, or Cloudflare needs a fresh clearance — open the Claude app " +
            "once and retry."
        429 -> "Rate limited by Claude (HTTP 429). Try again in a bit."
        else -> "Claude returned HTTP $code." + if (detail.isNotEmpty()) " $detail" else ""
    }
}
