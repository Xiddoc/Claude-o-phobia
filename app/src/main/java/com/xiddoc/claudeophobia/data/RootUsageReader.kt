package com.xiddoc.claudeophobia.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls live Claude usage by:
 *  1. using root (via [libsu][Shell]) to read the Claude app's cookie jar
 *     (shared_prefs), and
 *  2. calling the same `/usage` endpoint the official client uses, forwarding
 *     those cookies (most importantly `sessionKey`).
 *
 * The Claude app does not cache usage on disk, so reading a file isn't enough —
 * we have to ask the server. Credentials live only in memory for the duration
 * of the request; secret values are never written to logs (see [UsageLog]).
 *
 * Root commands run through libsu rather than a hand-rolled `ProcessBuilder("su",
 * "-c", …)`. The old approach span a fresh, non-interactive `su -c` shell per
 * command, and on Magisk that shell ran in a context where `find`/`grep` over
 * the target app's data dir silently failed (exit 1/2, no output) even though the
 * very same commands worked in an interactive `su` session. libsu keeps a single
 * persistent interactive root shell and pipes each command to it over stdin — the
 * same execution model as a real `su` session — so those reads now succeed.
 *
 * The whole sequence is heavily traced under the `ClaudeUsage` logcat tag so a
 * failing read can be diagnosed step-by-step:
 *
 * ```
 * adb logcat -s ClaudeUsage
 * ```
 */
class RootUsageReader(
    private val packageName: String,
) {
    suspend fun read(): RootResult = withContext(Dispatchers.IO) {
        UsageLog.d("read(): starting live-usage read for package='$packageName'")

        if (!isRootAvailable()) {
            UsageLog.w("read(): no root shell available — returning NoRoot")
            return@withContext RootResult.NoRoot
        }
        UsageLog.d("read(): root shell confirmed (uid=0)")

        val cookies = readCookieJar()
        if (cookies == null) {
            UsageLog.w("read(): cookie jar not found / unreadable for '$packageName' — returning NotFound")
            return@withContext RootResult.NotFound(
                "Couldn't read a session cookie from $packageName. Make sure you're " +
                    "signed in to the Claude app, and that the package name is correct."
            )
        }
        UsageLog.d("read(): parsed ${cookies.size} cookie(s): ${cookies.keys.joinToString(", ")}")

        val sessionKey = cookies["sessionKey"]
        if (sessionKey.isNullOrBlank()) {
            UsageLog.w("read(): cookie jar present but sessionKey is blank — returning NotFound")
            return@withContext RootResult.NotFound(
                "Found the cookie jar but no sessionKey inside — are you logged in?"
            )
        }
        UsageLog.d("read(): sessionKey present -> ${UsageLog.redact(sessionKey)}")

        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        UsageLog.d("read(): built Cookie header (${cookieHeader.length} chars, ${cookies.size} pairs)")

        val orgFromCookie = cookies["lastActiveOrg"]?.takeIf { it.isNotBlank() }
        if (orgFromCookie != null) {
            UsageLog.d("read(): using lastActiveOrg from cookie -> ${UsageLog.redact(orgFromCookie)}")
        } else {
            UsageLog.d("read(): no lastActiveOrg cookie — discovering org id from /api/organizations")
        }
        val orgId = orgFromCookie
            ?: ClaudeApi.fetchFirstOrgId(cookieHeader)
            ?: run {
                UsageLog.w("read(): could not determine an organization id — returning NotFound")
                return@withContext RootResult.NotFound(
                    "Couldn't determine your organization id from the cookies."
                )
            }
        UsageLog.d("read(): resolved org id -> ${UsageLog.redact(orgId)}")

        return@withContext try {
            val snapshot = ClaudeApi.fetchUsage(orgId, cookieHeader)
            UsageLog.d(
                "read(): SUCCESS weekly=${snapshot.weeklyUtilizationPercent}% " +
                    "fiveHour=${snapshot.fiveHourUtilizationPercent}% " +
                    "weeklyResetMs=${snapshot.weeklyResetEpochMs} " +
                    "fiveHourResetMs=${snapshot.fiveHourResetEpochMs}"
            )
            RootResult.Found(snapshot)
        } catch (e: ClaudeApi.HttpException) {
            UsageLog.e("read(): Claude returned HTTP ${e.code}: ${e.message}", e)
            RootResult.Error(e.message ?: "Claude returned HTTP ${e.code}.")
        } catch (e: Exception) {
            UsageLog.e("read(): unexpected failure talking to Claude", e)
            RootResult.Error("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- root plumbing ----------------------------------------------------

    private fun isRootAvailable(): Boolean {
        UsageLog.d("isRootAvailable(): requesting a root shell from libsu")
        // getShell() blocks until the shell is constructed (and, on first use,
        // until the superuser prompt is answered). isRoot reflects the uid the
        // shell actually obtained.
        val root = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            UsageLog.w("isRootAvailable(): failed to obtain a shell", e)
            false
        }
        UsageLog.d("isRootAvailable(): shell isRoot=$root")
        return root
    }

    /**
     * Locates the cookie store and extracts name/value pairs. The Claude app
     * stores cookies as HTML-escaped JSON inside a `shared_prefs/user_cookies_*.xml`
     * file, e.g. `...&quot;name&quot;:&quot;sessionKey&quot;,&quot;value&quot;:&quot;...&quot;...`
     */
    private fun readCookieJar(): Map<String, String>? {
        val dirs = listOf("/data/data/$packageName", "/data/user/0/$packageName")
        val findCmd = dirs.joinToString("; ") { dir ->
            "find '$dir' -name 'user_cookies_*.xml' 2>/dev/null"
        }
        UsageLog.d("readCookieJar(): searching for user_cookies_*.xml in ${dirs.joinToString(", ")}")
        var file = runSu(findCmd)?.lineSequence()
            ?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        UsageLog.d("readCookieJar(): name-based find -> ${file ?: "<none>"}")

        // Fallback: any file that actually contains a session cookie.
        if (file.isNullOrEmpty()) {
            val grepCmd = dirs.joinToString("; ") { dir ->
                "grep -rIl 'sessionKey' '$dir' 2>/dev/null"
            }
            UsageLog.d("readCookieJar(): name-based find empty — falling back to grep for 'sessionKey'")
            file = runSu(grepCmd)?.lineSequence()
                ?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            UsageLog.d("readCookieJar(): grep fallback -> ${file ?: "<none>"}")
        }
        if (file.isNullOrEmpty()) {
            UsageLog.w("readCookieJar(): no cookie file located")
            return null
        }

        val raw = runSu("cat '$file' 2>/dev/null")?.takeIf { it.isNotBlank() }
        if (raw == null) {
            UsageLog.w("readCookieJar(): `cat` of '$file' produced no content")
            return null
        }
        UsageLog.d("readCookieJar(): read ${raw.length} chars from '$file'")
        val cookies = parseCookies(raw)
        UsageLog.d("readCookieJar(): extracted ${cookies.size} cookie pair(s)")
        return cookies.ifEmpty {
            UsageLog.w("readCookieJar(): file had no parseable cookie pairs")
            null
        }
    }

    /** Unescapes XML entities and pulls every {"name":...,"value":...} pair. */
    private fun parseCookies(raw: String): Map<String, String> {
        val unescaped = raw
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
        val result = LinkedHashMap<String, String>()
        for (match in COOKIE_PAIR.findAll(unescaped)) {
            val name = match.groupValues[1]
            val value = match.groupValues[2]
            if (name.isNotEmpty() && value.isNotEmpty() && '\n' !in value) {
                result[name] = value
            }
        }
        return result
    }

    /**
     * Runs [command] in libsu's shared root shell and returns its stdout as a
     * single string (or null if the command couldn't be run / exited non-zero
     * with no output). stderr is left out of the result on purpose — callers
     * already redirect it with `2>/dev/null` — but we never log stdout itself,
     * since it may hold the raw cookie jar.
     */
    private fun runSu(command: String): String? {
        UsageLog.d("runSu(): exec ${command.take(160)}")
        return try {
            val result = Shell.cmd(command).exec()
            val output = result.out.joinToString("\n")
            UsageLog.d("runSu(): exit=${result.code}, read ${output.length} chars of output")
            output.ifEmpty { null }
        } catch (e: Exception) {
            UsageLog.w("runSu(): command failed to run", e)
            null
        }
    }

    companion object {
        const val DEFAULT_PACKAGE = "com.anthropic.claude"

        init {
            // Configure the process-wide shell before anything asks for one.
            // A generous timeout covers the superuser prompt on first launch;
            // commands themselves are fast.
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(SHELL_TIMEOUT_SECONDS)
            )
        }

        private const val SHELL_TIMEOUT_SECONDS = 10L

        // "name":"<cookie>","value":"<value>" — order as stored by the app.
        private val COOKIE_PAIR =
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]*)\"")
    }
}
