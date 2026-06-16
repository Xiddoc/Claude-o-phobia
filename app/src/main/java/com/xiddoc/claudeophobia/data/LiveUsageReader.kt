package com.xiddoc.claudeophobia.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns the credentials the LSPosed module captured into a live [UsageResult].
 *
 * The module (inside the Claude process) only couriers the session cookie + org
 * id over to us; the actual `claude.ai/.../usage` request is made here, in our
 * own app, exactly like the old root reader did. That's what makes Retry and the
 * background refresh real network calls rather than a re-read of stale state.
 *
 * The whole sequence is traced under the `ClaudeUsage` logcat tag:
 *
 * ```
 * adb logcat -s ClaudeUsage
 * ```
 */
class LiveUsageReader {

    suspend fun read(settings: AppSettings, rebootNeeded: Boolean): UsageResult =
        withContext(Dispatchers.IO) {
            UsageLog.d(
                "read(): enabled=${settings.liveUsageEnabled} hasCredentials=${settings.hasCredentials} " +
                    "rebootNeeded=$rebootNeeded credAgeMs=${ageOf(settings.credentialsCapturedAtMs)}"
            )

            LiveUsage.preFetchState(
                enabled = settings.liveUsageEnabled,
                hasCredentials = settings.hasCredentials,
                rebootNeeded = rebootNeeded,
            )?.let { early ->
                UsageLog.d("read(): no fetch — returning ${early.javaClass.simpleName}")
                return@withContext early
            }

            val cookieHeader = settings.cookieHeader.orEmpty()
            UsageLog.d("read(): have cookie header (${cookieHeader.length} chars)")

            val orgId = settings.orgId?.takeIf { it.isNotBlank() }
                ?.also { UsageLog.d("read(): using captured org id ${UsageLog.redact(it)}") }
                ?: run {
                    UsageLog.d("read(): no captured org id — discovering from /api/organizations")
                    ClaudeApi.fetchFirstOrgId(cookieHeader)
                }
                ?: run {
                    UsageLog.w("read(): could not determine an organization id — NotFound")
                    return@withContext UsageResult.NotFound("Couldn't determine your organization id.")
                }
            UsageLog.d("read(): resolved org id ${UsageLog.redact(orgId)}")

            return@withContext try {
                val snapshot = ClaudeApi.fetchUsage(orgId, cookieHeader)
                UsageLog.d(
                    "read(): SUCCESS weekly=${snapshot.weeklyUtilizationPercent}% " +
                        "fiveHour=${snapshot.fiveHourUtilizationPercent}% " +
                        "weeklyResetMs=${snapshot.weeklyResetEpochMs}"
                )
                UsageResult.Found(snapshot)
            } catch (e: ClaudeApi.HttpException) {
                UsageLog.e("read(): Claude returned HTTP ${e.code}: ${e.message}", e)
                UsageResult.Error(e.message ?: "Claude returned HTTP ${e.code}.")
            } catch (e: Exception) {
                UsageLog.e("read(): unexpected failure talking to Claude", e)
                UsageResult.Error("Network error: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun ageOf(timestampMs: Long): Long =
        if (timestampMs <= 0L) -1L else System.currentTimeMillis() - timestampMs
}
