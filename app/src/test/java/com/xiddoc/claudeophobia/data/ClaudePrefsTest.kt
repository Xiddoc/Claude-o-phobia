package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClaudePrefsTest {

    // Shape of the Claude app's shared_prefs/account_prefs<id>.xml. The
    // selected_org_id is a fabricated UUID — only the structure is real.
    private val sampleAccountPrefs = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <boolean name="has_seen_intro" value="true" />
            <string name="selected_org_id">00000000-1111-2222-3333-444444444444</string>
            <long name="last_sync" value="1783903019000" />
        </map>
    """.trimIndent()

    // Shape of the Claude app's shared_prefs/user_cookies_*.xml cookie jar.
    // Every value here is fabricated — this only mirrors the *structure*:
    // a SharedPreferences <map> whose <string> entries hold XML-escaped JSON
    // cookie objects. `sessionKeyV2` is the empty-sentinel case the real app
    // writes when the cookie is unset (value is a JSON `""`).
    private val sampleJar = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="https://claude.ai/|sessionKey">{&quot;name&quot;:&quot;sessionKey&quot;,&quot;value&quot;:&quot;sk-ant-sid02-FAKEFAKEFAKE&quot;,&quot;expiresAt&quot;:1783903019000,&quot;domain&quot;:&quot;claude.ai&quot;,&quot;path&quot;:&quot;/&quot;,&quot;secure&quot;:true,&quot;httpOnly&quot;:true,&quot;hostOnly&quot;:false}</string>
            <string name="https://claude.ai/|_cfuvid">{&quot;name&quot;:&quot;_cfuvid&quot;,&quot;value&quot;:&quot;abc.def-1.0.1.1-not_a_real_value&quot;,&quot;domain&quot;:&quot;claude.ai&quot;,&quot;path&quot;:&quot;/&quot;,&quot;secure&quot;:true,&quot;httpOnly&quot;:true,&quot;hostOnly&quot;:false}</string>
            <string name="https://claude.ai/|sessionKeyV2">{&quot;name&quot;:&quot;sessionKeyV2&quot;,&quot;value&quot;:&quot;\&quot;\&quot;&quot;,&quot;expiresAt&quot;:-9223372036854775808,&quot;domain&quot;:&quot;claude.ai&quot;,&quot;path&quot;:&quot;/&quot;,&quot;secure&quot;:true,&quot;httpOnly&quot;:true,&quot;hostOnly&quot;:false}</string>
        </map>
    """.trimIndent()

    // The pre-login flow jar (user_cookies_login*.xml): real cookies, but no
    // signed-in session yet, so no usable sessionKey.
    private val sampleLoginJar = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="https://claude.ai/|_cfuvid">{&quot;name&quot;:&quot;_cfuvid&quot;,&quot;value&quot;:&quot;login.flow-not_a_real_value&quot;,&quot;domain&quot;:&quot;claude.ai&quot;,&quot;path&quot;:&quot;/&quot;}</string>
            <string name="https://claude.ai/|sessionKey">{&quot;name&quot;:&quot;sessionKey&quot;,&quot;value&quot;:&quot;\&quot;\&quot;&quot;,&quot;domain&quot;:&quot;claude.ai&quot;,&quot;path&quot;:&quot;/&quot;}</string>
        </map>
    """.trimIndent()

    private fun tempDataDirWith(vararg jars: Pair<String, String>): File {
        val dataDir = Files.createTempDirectory("claude-data").toFile()
        val prefs = File(dataDir, "shared_prefs").apply { mkdirs() }
        jars.forEach { (name, body) -> File(prefs, name).writeText(body) }
        return dataDir
    }

    @Test
    fun readsAccountJarEvenWhenAnotherCookieJarIsPresent() {
        // Two jars side by side: the login-flow jar (no session) and the real
        // signed-in account jar. We must not just grab whichever listFiles()
        // returns first — we have to land on the one carrying the sessionKey.
        val dataDir = tempDataDirWith(
            "user_cookies_login.xml" to sampleLoginJar,
            "user_cookies_8c46766e-36be-4a08-a7c7-13ea45d904bc.xml" to sampleJar,
        )

        val cookies = ClaudePrefs.readCookies(dataDir)
        assertEquals("sk-ant-sid02-FAKEFAKEFAKE", cookies["sessionKey"])
    }

    @Test
    fun prefersProductionAccountJarOverStaging() {
        val stagingJar = sampleJar.replace("sk-ant-sid02-FAKEFAKEFAKE", "sk-ant-sid02-STAGING")
        val dataDir = tempDataDirWith(
            "user_cookies___https:--claude-ai.staging.ant.dev__8c46766e.xml" to stagingJar,
            "user_cookies_8c46766e-36be-4a08-a7c7-13ea45d904bc.xml" to sampleJar,
        )

        val cookies = ClaudePrefs.readCookies(dataDir)
        assertEquals("sk-ant-sid02-FAKEFAKEFAKE", cookies["sessionKey"])
    }

    @Test
    fun returnsEmptyWhenNoJarPresent() {
        assertTrue(ClaudePrefs.readCookies(tempDataDirWith()).isEmpty())
    }

    @Test
    fun extractsCookieFromInnerJsonNotTheAttributeKey() {
        val cookies = ClaudePrefs.parseCookies(sampleJar)

        // The key is the cookie's JSON `name`, not the "<url>|<cookie>" attribute.
        assertTrue("expected a sessionKey entry", cookies.containsKey("sessionKey"))
        assertFalse(cookies.containsKey("https://claude.ai/|sessionKey"))
        assertEquals("sk-ant-sid02-FAKEFAKEFAKE", cookies["sessionKey"])
        assertEquals("abc.def-1.0.1.1-not_a_real_value", cookies["_cfuvid"])
    }

    @Test
    fun dropsEmptySentinelCookies() {
        val cookies = ClaudePrefs.parseCookies(sampleJar)
        assertFalse("unset sessionKeyV2 should be skipped", cookies.containsKey("sessionKeyV2"))
    }

    @Test
    fun preservesStoredOrder() {
        val cookies = ClaudePrefs.parseCookies(sampleJar)
        assertEquals(listOf("sessionKey", "_cfuvid"), cookies.keys.toList())
    }

    @Test
    fun buildsCookieHeaderFromPairs() {
        val cookies = ClaudePrefs.parseCookies(sampleJar)
        assertEquals(
            "sessionKey=sk-ant-sid02-FAKEFAKEFAKE; _cfuvid=abc.def-1.0.1.1-not_a_real_value",
            ClaudePrefs.cookieHeader(cookies),
        )
    }

    @Test
    fun returnsEmptyForGarbageInsteadOfThrowing() {
        assertTrue(ClaudePrefs.parseCookies("not xml at all").isEmpty())
        assertTrue(ClaudePrefs.parseCookies("").isEmpty())
    }

    @Test
    fun readsSelectedOrgIdFromAccountPrefs() {
        assertEquals(
            "00000000-1111-2222-3333-444444444444",
            ClaudePrefs.parsePrefString(sampleAccountPrefs, "selected_org_id"),
        )
    }

    @Test
    fun prefStringIsNullForMissingKeyOrGarbage() {
        assertNull(ClaudePrefs.parsePrefString(sampleAccountPrefs, "not_a_key"))
        assertNull(ClaudePrefs.parsePrefString("not xml at all", "selected_org_id"))
    }
}
