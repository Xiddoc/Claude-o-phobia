package com.xiddoc.claudeophobia.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Reads Claude usage out of the Claude app's private storage using root (`su`).
 *
 * Important: the Claude Android app does not publish a stable on-device format
 * for usage/rate-limit state, so this reader is deliberately heuristic. It
 * greps the app's data directory for likely JSON, then pattern-matches keys
 * that look like utilization percentages and reset timestamps. If Anthropic
 * changes their storage, tweak the *_HINTS regexes below — the rest of the app
 * keeps working without any of this.
 */
class RootUsageReader(
    private val packageName: String,
) {
    suspend fun read(): RootResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext RootResult.NoRoot

        val dataDirs = listOf(
            "/data/data/$packageName",
            "/data/user/0/$packageName",
        )

        // 1. Find files that mention rate-limit-ish keywords.
        val grepKeywords = "rate_limit|utilization|resets_at|reset_at|five_hour|seven_day|weekly|usage_limit|window"
        val findCmd = dataDirs.joinToString("; ") { dir ->
            "grep -rIlE '$grepKeywords' '$dir' 2>/dev/null"
        }
        val candidateFiles = runSu(findCmd)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(MAX_FILES)
            ?.toList()
            ?: return@withContext RootResult.Error("Could not list Claude data files.")

        if (candidateFiles.isEmpty()) {
            return@withContext RootResult.NotFound(
                "No rate-limit data found under $packageName. " +
                    "Open the Claude app, check your usage once, then retry."
            )
        }

        // 2. Read the candidates (capped) and concatenate for parsing.
        val blob = StringBuilder()
        var source: String? = null
        for (file in candidateFiles) {
            val content = runSu("cat '$file' 2>/dev/null") ?: continue
            if (content.isBlank()) continue
            if (source == null) source = file
            blob.append(content).append('\n')
            if (blob.length > MAX_BLOB_CHARS) break
        }

        val snapshot = parse(blob.toString(), source)
        if (snapshot.hasAnything) {
            RootResult.Found(snapshot)
        } else {
            RootResult.NotFound(
                "Found Claude data but couldn't recognise any usage fields. " +
                    "The app's storage format may have changed."
            )
        }
    }

    // ---- root plumbing ----------------------------------------------------

    private suspend fun isRootAvailable(): Boolean {
        val out = runSu("id") ?: return false
        return out.contains("uid=0")
    }

    private suspend fun runSu(command: String): String? = withTimeoutOrNull(SU_TIMEOUT_MS) {
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            process.waitFor()
            output
        } catch (_: Exception) {
            null
        }
    }

    // ---- parsing ----------------------------------------------------------

    private fun parse(blob: String, source: String?): UsageSnapshot {
        val pairs = KEY_VALUE.findAll(blob)
            .map { it.groupValues[1] to it.groupValues[2].trim().trim('"') }
            .toList()

        val weeklyUtil = pickPercent(pairs, WEEKLY_HINTS)
        val fiveHourUtil = pickPercent(pairs, FIVE_HOUR_HINTS)
        val weeklyReset = pickTimestamp(pairs, WEEKLY_HINTS)
        val fiveHourReset = pickTimestamp(pairs, FIVE_HOUR_HINTS)

        return UsageSnapshot(
            weeklyUtilizationPercent = weeklyUtil,
            weeklyResetEpochMs = weeklyReset,
            fiveHourUtilizationPercent = fiveHourUtil,
            fiveHourResetEpochMs = fiveHourReset,
            sourceLabel = source?.substringAfterLast('/'),
        )
    }

    /** Finds a numeric value whose key matches a window hint and a usage hint. */
    private fun pickPercent(pairs: List<Pair<String, String>>, windowHints: Regex): Double? {
        for ((key, value) in pairs) {
            val k = key.lowercase()
            if (!windowHints.containsMatchIn(k)) continue
            if (!USAGE_HINTS.containsMatchIn(k)) continue
            val number = value.toDoubleOrNull() ?: continue
            // Normalise a 0..1 fraction up to a percentage.
            return if (number in 0.0..1.0) number * 100.0 else number
        }
        return null
    }

    /** Finds a reset/expiry timestamp whose key matches a window hint. */
    private fun pickTimestamp(pairs: List<Pair<String, String>>, windowHints: Regex): Long? {
        for ((key, value) in pairs) {
            val k = key.lowercase()
            if (!windowHints.containsMatchIn(k)) continue
            if (!RESET_HINTS.containsMatchIn(k)) continue
            parseEpochMillis(value)?.let { return it }
        }
        return null
    }

    private fun parseEpochMillis(raw: String): Long? {
        val value = raw.trim().trim('"')
        // Numeric epoch (seconds or millis).
        value.toLongOrNull()?.let { n ->
            return if (n > 100_000_000_000L) n else n * 1000L
        }
        // ISO-8601 instant, e.g. 2026-06-18T08:00:00Z.
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        const val DEFAULT_PACKAGE = "com.anthropic.claude"

        private const val MAX_FILES = 8
        private const val MAX_BLOB_CHARS = 400_000
        private const val SU_TIMEOUT_MS = 6_000L

        // Generic "key": value extractor (string or number values).
        private val KEY_VALUE = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(\"[^\"]*\"|[0-9.]+)")

        // Tune these if Anthropic renames things.
        private val WEEKLY_HINTS = Regex("seven_day|7d|weekly|week")
        private val FIVE_HOUR_HINTS = Regex("five_hour|5h|rolling|short|hourly")
        private val USAGE_HINTS = Regex("util|used|usage|percent|consumed|remaining|count")
        private val RESET_HINTS = Regex("reset|resets|expire|expires|end|until|next")
    }
}
