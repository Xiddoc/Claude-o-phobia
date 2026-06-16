package com.xiddoc.claudeophobia.data

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

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
 * Filesystem access runs through libsu rather than a hand-rolled
 * `ProcessBuilder("su", "-c", …)`. The old approach span a fresh, non-interactive
 * `su -c` shell per command, and on Magisk that shell ran in a context where
 * `find`/`grep` over the target app's data dir silently failed (exit 1/2, no
 * output) even though the very same commands worked in an interactive `su`
 * session. libsu keeps a single persistent interactive root shell, so those reads
 * now succeed.
 *
 * On top of that shell we use libsu's `java.io.File`-shaped API ([SuFile] /
 * [SuFileInputStream]) instead of stitching together `find`/`ls`/`cat` strings:
 * we list the target app's `shared_prefs/` directory and read the files we want
 * as streams. No shell metacharacters, globbing, or output parsing to get wrong.
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

        // Prefer the org the app itself has selected (account_prefs), then the
        // lastActiveOrg cookie, and only then ask the server to enumerate orgs.
        val orgId = readSelectedOrgId()
            ?: cookies["lastActiveOrg"]?.takeIf { it.isNotBlank() }
                ?.also { UsageLog.d("read(): using lastActiveOrg cookie -> ${UsageLog.redact(it)}") }
            ?: run {
                UsageLog.d("read(): no selected_org_id / lastActiveOrg — discovering org id from /api/organizations")
                ClaudeApi.fetchFirstOrgId(cookieHeader)
            }
            ?: run {
                UsageLog.w("read(): could not determine an organization id — returning NotFound")
                return@withContext RootResult.NotFound(
                    "Couldn't determine your organization id."
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
     * keeps its cookies in a standard Android SharedPreferences file named
     * `shared_prefs/user_cookies_<accountId>.xml`. See [parseCookies] for the
     * on-disk shape.
     */
    private fun readCookieJar(): Map<String, String>? {
        val file = findPrefsFile { it.startsWith("user_cookies_") && it.endsWith(".xml") }
        if (file == null) {
            UsageLog.w("readCookieJar(): no user_cookies_*.xml under shared_prefs")
            return null
        }
        val raw = readTextAsRoot(file) ?: run {
            UsageLog.w("readCookieJar(): '${file.path}' produced no content")
            return null
        }
        UsageLog.d("readCookieJar(): read ${raw.length} chars from '${file.path}'")
        val cookies = parseCookies(raw)
        UsageLog.d("readCookieJar(): extracted ${cookies.size} cookie pair(s)")
        return cookies.ifEmpty {
            UsageLog.w("readCookieJar(): file had no parseable cookie pairs")
            null
        }
    }

    /**
     * Reads the org the Claude app currently has selected. It is stored as a
     * plain `<string name="selected_org_id">…</string>` entry in
     * `shared_prefs/account_prefs<accountId>.xml`.
     */
    private fun readSelectedOrgId(): String? {
        val file = findPrefsFile { it.startsWith("account_prefs") && it.endsWith(".xml") }
        if (file == null) {
            UsageLog.d("readSelectedOrgId(): no account_prefs*.xml under shared_prefs")
            return null
        }
        val raw = readTextAsRoot(file) ?: return null
        val orgId = parsePrefString(raw, "selected_org_id")
        if (orgId == null) {
            UsageLog.d("readSelectedOrgId(): account_prefs had no selected_org_id")
        } else {
            UsageLog.d("readSelectedOrgId(): selected_org_id -> ${UsageLog.redact(orgId)}")
        }
        return orgId
    }

    // ---- libsu File helpers ----------------------------------------------

    /** Candidate data dirs for the target app, primary first. */
    private val dataDirs: List<String>
        get() = listOf("/data/data/$packageName", "/data/user/0/$packageName")

    /**
     * Finds the first file in the target app's `shared_prefs/` whose name
     * satisfies [nameMatches]. Listing happens through libsu's root-backed
     * [SuFile], so there's no shell command to escape or parse.
     */
    private fun findPrefsFile(nameMatches: (String) -> Boolean): SuFile? {
        for (base in dataDirs) {
            val prefsDir = SuFile(base, "shared_prefs")
            if (!prefsDir.isDirectory) continue
            val match = prefsDir.listFiles()?.firstOrNull { nameMatches(it.name) } ?: continue
            UsageLog.d("findPrefsFile(): matched ${match.path}")
            return SuFile(match.absolutePath)
        }
        return null
    }

    /** Reads a root-owned file's text via [SuFileInputStream], or null. */
    private fun readTextAsRoot(file: SuFile): String? = try {
        SuFileInputStream.open(file).bufferedReader().use { it.readText() }
            .takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        UsageLog.w("readTextAsRoot(): couldn't read ${file.path}", e)
        null
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

        /**
         * Parses the Claude app's `shared_prefs/user_cookies_*.xml` into cookie
         * `name -> value` pairs.
         *
         * That file is a plain Android SharedPreferences map. Each cookie is one
         * `<string>` entry whose (XML-escaped) text is a JSON object describing
         * the cookie:
         *
         * ```
         * <map>
         *   <string name="https://claude.ai/|sessionKey">
         *     {"name":"sessionKey","value":"sk-ant-…","expiresAt":…,"domain":"claude.ai",…}
         *   </string>
         *   …
         * </map>
         * ```
         *
         * We let a real XML parser do the entity unescaping and a real JSON
         * parser read each entry, instead of regexing across `&quot;` boundaries
         * and hoping we land on the right `"value"`. The pair we trust is the
         * `name`/`value` *inside* the JSON — the element's `name=` attribute is
         * only a `"<url>|<cookie>"` lookup key, not the cookie itself.
         *
         * Sentinel entries that carry no real cookie (e.g. an unset
         * `sessionKeyV2` whose value is an empty `""`) are dropped.
         *
         * Visible for testing.
         */
        internal fun parseCookies(raw: String): Map<String, String> {
            val doc = parseXml(raw) ?: return emptyMap()
            val entries = doc.getElementsByTagName("string")
            val result = LinkedHashMap<String, String>()
            for (i in 0 until entries.length) {
                val json = entries.item(i).textContent?.trim().orEmpty()
                if (json.isEmpty()) continue
                runCatching {
                    val obj = JSONObject(json)
                    val name = obj.optString("name").trim()
                    val value = obj.optString("value")
                    if (name.isNotEmpty() && isUsableCookieValue(value)) {
                        result[name] = value
                    }
                }
            }
            return result
        }

        /**
         * Reads a single `<string name="[key]">value</string>` entry out of a
         * standard Android SharedPreferences XML document (the same `<map>`
         * format as the cookie jar, but here the value is plain text — e.g.
         * `selected_org_id` in `account_prefs*.xml`). Returns null when the key
         * is absent or its value is blank.
         *
         * Visible for testing.
         */
        internal fun parsePrefString(raw: String, key: String): String? {
            val doc = parseXml(raw) ?: return null
            val entries = doc.getElementsByTagName("string")
            for (i in 0 until entries.length) {
                val el = entries.item(i) as? Element ?: continue
                if (el.getAttribute("name") == key) {
                    return el.textContent?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            return null
        }

        /** Parses [raw] into a DOM, or null if it isn't well-formed XML. */
        private fun parseXml(raw: String): Document? = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // Reading a local prefs file never needs DOCTYPEs or external
                // entities; turn them off where supported.
                runCatching { setFeature(FEATURE_DISALLOW_DOCTYPE, true) }
                runCatching { setFeature(FEATURE_EXTERNAL_GENERAL, false) }
                runCatching { setFeature(FEATURE_EXTERNAL_PARAMETER, false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
        } catch (_: Exception) {
            null
        }

        /**
         * An unset cookie is stored as an empty sentinel — e.g. `sessionKeyV2`
         * has a JSON value of `""` (a string whose content is two quote
         * characters) when there's nothing in it. Such entries carry no usable
         * cookie, so we don't forward them.
         */
        private fun isUsableCookieValue(value: String): Boolean =
            value.trim().trim('"').isNotEmpty()

        private const val FEATURE_DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl"
        private const val FEATURE_EXTERNAL_GENERAL =
            "http://xml.org/sax/features/external-general-entities"
        private const val FEATURE_EXTERNAL_PARAMETER =
            "http://xml.org/sax/features/external-parameter-entities"
    }
}
