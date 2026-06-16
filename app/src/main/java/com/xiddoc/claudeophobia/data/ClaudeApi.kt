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
 * `sessionKey`), which are read from the Claude app's own cookie jar via root —
 * this class never stores, logs, or hard-codes any of them.
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

    // Matches the browser/app client so a Cloudflare clearance cookie bound to
    // that User-Agent keeps working when forwarded.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Mobile Safari/537.36"

    /** Thrown for non-2xx responses so callers can show a precise reason. */
    class HttpException(val code: Int, message: String) : IOException(message)

    fun fetchUsage(orgId: String, cookieHeader: String): UsageSnapshot {
        val body = httpGet("$BASE/api/organizations/$orgId/usage", cookieHeader)
        return parseUsage(body)
    }

    /** Best-effort discovery of an organization id when no cookie supplies one. */
    fun fetchFirstOrgId(cookieHeader: String): String? {
        val body = runCatching { httpGet("$BASE/api/organizations", cookieHeader) }
            .getOrNull() ?: return null
        return runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("uuid")?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            null
        }.getOrNull()
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

    private fun httpGet(urlStr: String, cookieHeader: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Cookie", cookieHeader)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            // Avoid zstd/br bodies HttpURLConnection can't decode.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("anthropic-client-platform", "web_claude_ai")
            setRequestProperty("Referer", "$BASE/")
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }
                ?.take(180)
                ?.replace(Regex("\\s+"), " ")
                .orEmpty()
            throw HttpException(code, reasonFor(code, detail))
        } finally {
            conn.disconnect()
        }
    }

    private fun reasonFor(code: Int, detail: String): String = when (code) {
        401, 403 -> "Claude rejected the request (HTTP $code). The session cookie may " +
            "be expired, or Cloudflare needs a fresh clearance — open the Claude app " +
            "once and retry."
        429 -> "Rate limited by Claude (HTTP 429). Try again in a bit."
        else -> "Claude returned HTTP $code." + if (detail.isNotEmpty()) " $detail" else ""
    }
}
