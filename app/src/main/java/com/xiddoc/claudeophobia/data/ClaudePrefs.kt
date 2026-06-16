package com.xiddoc.claudeophobia.data

import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads and parses the Claude app's on-disk credential store.
 *
 * Unlike the old root-based reader, the LSPosed module runs *inside* the Claude
 * process, so it owns these files outright — no `su`, no [SuFile][], just plain
 * [File] reads against the app's own `shared_prefs/` directory. The parsing
 * itself (cookie jar + selected org) is identical to before and is exercised by
 * [ClaudePrefsTest].
 */
object ClaudePrefs {

    /** The stock Claude Android package; also the suggested LSPosed scope. */
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    // ---- in-process reads (module side) ----------------------------------

    /**
     * Reads the Claude session cookies out of the app's own `shared_prefs`. Runs
     * inside the hooked Claude process, so [dataDir] is `context.dataDir` and the
     * files are directly readable.
     */
    fun readCookies(dataDir: File): Map<String, String> {
        val jar = findPrefsFile(dataDir) {
            it.startsWith("user_cookies_") && it.endsWith(".xml")
        } ?: return emptyMap()
        return parseCookies(jar.readText())
    }

    /** Reads the org the Claude app currently has selected, if any. */
    fun readSelectedOrgId(dataDir: File): String? {
        val prefs = findPrefsFile(dataDir) {
            it.startsWith("account_prefs") && it.endsWith(".xml")
        } ?: return null
        return parsePrefString(prefs.readText(), "selected_org_id")
    }

    /** Builds the `name=value; …` Cookie header value from a parsed jar. */
    fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun findPrefsFile(dataDir: File, nameMatches: (String) -> Boolean): File? {
        val prefsDir = File(dataDir, "shared_prefs")
        if (!prefsDir.isDirectory) return null
        return prefsDir.listFiles()?.firstOrNull { it.isFile && nameMatches(it.name) }
    }

    // ---- parsing (shared, unit-tested) -----------------------------------

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
     * We let a real XML parser do the entity unescaping and a real JSON parser
     * read each entry, instead of regexing across `&quot;` boundaries and hoping
     * we land on the right `"value"`. The pair we trust is the `name`/`value`
     * *inside* the JSON — the element's `name=` attribute is only a
     * `"<url>|<cookie>"` lookup key, not the cookie itself.
     *
     * Sentinel entries that carry no real cookie (e.g. an unset `sessionKeyV2`
     * whose value is an empty `""`) are dropped.
     *
     * Visible for testing.
     */
    fun parseCookies(raw: String): Map<String, String> {
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
     * standard Android SharedPreferences XML document (the same `<map>` format
     * as the cookie jar, but here the value is plain text — e.g.
     * `selected_org_id` in `account_prefs*.xml`). Returns null when the key is
     * absent or its value is blank.
     *
     * Visible for testing.
     */
    fun parsePrefString(raw: String, key: String): String? {
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
     * An unset cookie is stored as an empty sentinel — e.g. `sessionKeyV2` has a
     * JSON value of `""` (a string whose content is two quote characters) when
     * there's nothing in it. Such entries carry no usable cookie, so we don't
     * forward them.
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
