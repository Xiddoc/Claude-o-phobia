package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootUsageReaderTest {

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

    @Test
    fun extractsCookieFromInnerJsonNotTheAttributeKey() {
        val cookies = RootUsageReader.parseCookies(sampleJar)

        // The key is the cookie's JSON `name`, not the "<url>|<cookie>" attribute.
        assertTrue("expected a sessionKey entry", cookies.containsKey("sessionKey"))
        assertFalse(cookies.containsKey("https://claude.ai/|sessionKey"))
        assertEquals("sk-ant-sid02-FAKEFAKEFAKE", cookies["sessionKey"])
        assertEquals("abc.def-1.0.1.1-not_a_real_value", cookies["_cfuvid"])
    }

    @Test
    fun dropsEmptySentinelCookies() {
        val cookies = RootUsageReader.parseCookies(sampleJar)
        assertFalse("unset sessionKeyV2 should be skipped", cookies.containsKey("sessionKeyV2"))
    }

    @Test
    fun preservesStoredOrder() {
        val cookies = RootUsageReader.parseCookies(sampleJar)
        assertEquals(listOf("sessionKey", "_cfuvid"), cookies.keys.toList())
    }

    @Test
    fun returnsEmptyForGarbageInsteadOfThrowing() {
        assertTrue(RootUsageReader.parseCookies("not xml at all").isEmpty())
        assertTrue(RootUsageReader.parseCookies("").isEmpty())
    }

    @Test
    fun readsSelectedOrgIdFromAccountPrefs() {
        assertEquals(
            "00000000-1111-2222-3333-444444444444",
            RootUsageReader.parsePrefString(sampleAccountPrefs, "selected_org_id"),
        )
    }

    @Test
    fun prefStringIsNullForMissingKeyOrGarbage() {
        assertNull(RootUsageReader.parsePrefString(sampleAccountPrefs, "not_a_key"))
        assertNull(RootUsageReader.parsePrefString("not xml at all", "selected_org_id"))
    }
}
