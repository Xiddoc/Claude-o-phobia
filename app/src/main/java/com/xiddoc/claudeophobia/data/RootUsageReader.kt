package com.xiddoc.claudeophobia.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader

/**
 * Pulls live Claude usage by:
 *  1. using `su` to read the Claude app's cookie jar (shared_prefs), and
 *  2. calling the same `/usage` endpoint the official client uses, forwarding
 *     those cookies (most importantly `sessionKey`).
 *
 * The Claude app does not cache usage on disk, so reading a file isn't enough —
 * we have to ask the server. Credentials live only in memory for the duration
 * of the request; nothing here is logged or persisted.
 */
class RootUsageReader(
    private val packageName: String,
) {
    suspend fun read(): RootResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext RootResult.NoRoot

        val cookies = readCookieJar()
            ?: return@withContext RootResult.NotFound(
                "Couldn't read a session cookie from $packageName. Make sure you're " +
                    "signed in to the Claude app, and that the package name is correct."
            )

        if (cookies["sessionKey"].isNullOrBlank()) {
            return@withContext RootResult.NotFound(
                "Found the cookie jar but no sessionKey inside — are you logged in?"
            )
        }

        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        val orgId = cookies["lastActiveOrg"]?.takeIf { it.isNotBlank() }
            ?: ClaudeApi.fetchFirstOrgId(cookieHeader)
            ?: return@withContext RootResult.NotFound(
                "Couldn't determine your organization id from the cookies."
            )

        try {
            RootResult.Found(ClaudeApi.fetchUsage(orgId, cookieHeader))
        } catch (e: ClaudeApi.HttpException) {
            RootResult.Error(e.message ?: "Claude returned HTTP ${e.code}.")
        } catch (e: Exception) {
            RootResult.Error("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- root plumbing ----------------------------------------------------

    private suspend fun isRootAvailable(): Boolean =
        runSu("id")?.contains("uid=0") == true

    /**
     * Locates the cookie store and extracts name/value pairs. The Claude app
     * stores cookies as HTML-escaped JSON inside a `shared_prefs/user_cookies_*.xml`
     * file, e.g. `...&quot;name&quot;:&quot;sessionKey&quot;,&quot;value&quot;:&quot;...&quot;...`
     */
    private suspend fun readCookieJar(): Map<String, String>? {
        val dirs = listOf("/data/data/$packageName", "/data/user/0/$packageName")
        val findCmd = dirs.joinToString("; ") { dir ->
            "find '$dir' -name 'user_cookies_*.xml' 2>/dev/null"
        }
        var file = runSu(findCmd)?.lineSequence()
            ?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

        // Fallback: any file that actually contains a session cookie.
        if (file.isNullOrEmpty()) {
            val grepCmd = dirs.joinToString("; ") { dir ->
                "grep -rIl 'sessionKey' '$dir' 2>/dev/null"
            }
            file = runSu(grepCmd)?.lineSequence()
                ?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        }
        if (file.isNullOrEmpty()) return null

        val raw = runSu("cat '$file' 2>/dev/null")?.takeIf { it.isNotBlank() } ?: return null
        val cookies = parseCookies(raw)
        return cookies.ifEmpty { null }
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

    companion object {
        const val DEFAULT_PACKAGE = "com.anthropic.claude"

        private const val SU_TIMEOUT_MS = 8_000L

        // "name":"<cookie>","value":"<value>" — order as stored by the app.
        private val COOKIE_PAIR =
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]*)\"")
    }
}
