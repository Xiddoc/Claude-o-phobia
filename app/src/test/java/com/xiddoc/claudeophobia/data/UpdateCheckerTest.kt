package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun stripsLeadingV() {
        assertEquals("1.0.42", VersionCompare.strip("v1.0.42"))
        assertEquals("1.0.42", VersionCompare.strip(" V1.0.42 "))
        assertEquals("1.0", VersionCompare.strip("1.0"))
    }

    @Test
    fun newerWhenAComponentIsHigher() {
        assertTrue(VersionCompare.isNewer("v1.0.43", "1.0.42"))
        assertTrue(VersionCompare.isNewer("1.1.0", "1.0.99"))
        assertTrue(VersionCompare.isNewer("2.0", "1.9.9"))
    }

    @Test
    fun notNewerWhenEqualOrOlder() {
        assertFalse(VersionCompare.isNewer("1.0.42", "1.0.42"))
        assertFalse(VersionCompare.isNewer("1.0.41", "1.0.42"))
        assertFalse(VersionCompare.isNewer("v1.0.0", "1.0.0"))
    }

    @Test
    fun differingComponentCountsComparePositionally() {
        // 1.0.5 > 1.0 (treated as 1.0.0)
        assertTrue(VersionCompare.isNewer("1.0.5", "1.0"))
        // 1.0 is not newer than 1.0.5
        assertFalse(VersionCompare.isNewer("1.0", "1.0.5"))
    }

    @Test
    fun blankOrUnparseableCandidateIsNeverNewer() {
        assertFalse(VersionCompare.isNewer("", "1.0.0"))
        assertFalse(VersionCompare.isNewer(null, "1.0.0"))
        assertFalse(VersionCompare.isNewer("nightly", "1.0.0"))
    }

    @Test
    fun parsesTagAndApkAssetFromReleasePayload() {
        val body = """
            {
              "tag_name": "v1.0.42",
              "name": "Claude-o-phobia v1.0.42",
              "html_url": "https://github.com/Xiddoc/Claude-o-phobia/releases/tag/v1.0.42",
              "assets": [
                {"browser_download_url": "https://example.com/notes.txt"},
                {"browser_download_url": "https://example.com/app-release.apk"}
              ]
            }
        """.trimIndent()
        val latest = UpdateChecker.parseLatest(body)
        assertEquals("1.0.42", latest.versionName)
        assertEquals("https://example.com/app-release.apk", latest.downloadUrl)
    }

    @Test
    fun extractsVersionFromTagOrName() {
        assertEquals("1.0.108", VersionCompare.extractVersion("v1.0.108"))
        assertEquals("1.0.108", VersionCompare.extractVersion("Claude-o-phobia v1.0.108"))
        assertEquals("2.0", VersionCompare.extractVersion("release 2.0 final"))
        assertEquals(null, VersionCompare.extractVersion("latest"))
        assertEquals(null, VersionCompare.extractVersion(""))
        assertEquals(null, VersionCompare.extractVersion(null))
    }

    @Test
    fun readsVersionFromNameWhenTagIsRollingLatest() {
        // CI publishes every master build under a single rolling `latest` tag and
        // keeps the real version in the release name. The check must still detect
        // updates instead of always reporting "up to date".
        val body = """
            {
              "tag_name": "latest",
              "name": "Claude-o-phobia v1.0.108",
              "html_url": "https://github.com/Xiddoc/Claude-o-phobia/releases/tag/latest",
              "assets": [
                {"browser_download_url": "https://example.com/app-release.apk"}
              ]
            }
        """.trimIndent()
        val latest = UpdateChecker.parseLatest(body)
        assertEquals("1.0.108", latest.versionName)
        assertEquals("https://example.com/app-release.apk", latest.downloadUrl)
        assertTrue(VersionCompare.isNewer(latest.versionName, "1.0.42"))
        assertFalse(VersionCompare.isNewer(latest.versionName, "1.0.108"))
    }

    @Test
    fun fallsBackToReleasePageWhenNoApkAsset() {
        val body = """
            {
              "tag_name": "v1.0.7",
              "html_url": "https://github.com/Xiddoc/Claude-o-phobia/releases/tag/v1.0.7",
              "assets": []
            }
        """.trimIndent()
        val latest = UpdateChecker.parseLatest(body)
        assertEquals("1.0.7", latest.versionName)
        assertEquals(
            "https://github.com/Xiddoc/Claude-o-phobia/releases/tag/v1.0.7",
            latest.downloadUrl,
        )
    }
}
