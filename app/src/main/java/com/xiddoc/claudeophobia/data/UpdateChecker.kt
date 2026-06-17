package com.xiddoc.claudeophobia.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer Claude-o-phobia build than the one running.
 *
 * The release workflow tags each master build `v1.0.<run>` and attaches the
 * optimized APK (see `.github/workflows/android.yml`), and the same run number is
 * baked into the installed `versionName`. So "is there an update?" is just: does
 * the latest release's version sort higher than ours? Nothing here is
 * authenticated — it's a plain GET against the public releases API.
 */
object UpdateChecker {

    /** Public repository the app lives in. */
    const val REPO_URL = "https://github.com/Xiddoc/Claude-o-phobia"

    /** Human-facing releases page, used as a download fallback. */
    const val RELEASES_PAGE = "$REPO_URL/releases"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/Xiddoc/Claude-o-phobia/releases/latest"

    /** The newest published release, distilled to what the About screen needs. */
    data class LatestRelease(
        /** Tag/version, with any leading `v` stripped (e.g. `1.0.42`). */
        val versionName: String,
        /** Where to grab it — the APK asset if present, else the release page. */
        val downloadUrl: String,
    )

    /**
     * Fetches the latest release from GitHub. Performs network I/O, so call it off
     * the main thread; throws on transport or non-2xx responses.
     */
    fun fetchLatest(): LatestRelease {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Claude-o-phobia")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("GitHub returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseLatest(body)
        } finally {
            conn.disconnect()
        }
    }

    /** Parses the `releases/latest` payload into a [LatestRelease]. Visible for testing. */
    fun parseLatest(body: String): LatestRelease {
        val root = JSONObject(body)
        val tag = root.optString("tag_name").ifBlank { root.optString("name") }
        val version = VersionCompare.strip(tag)

        // Prefer the attached .apk asset so "Download" lands straight on the file;
        // fall back to the release page (and then the releases tab) otherwise.
        val assets = root.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val url = assets.optJSONObject(i)?.optString("browser_download_url").orEmpty()
                if (url.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = url
                    break
                }
            }
        }
        val download = apkUrl
            ?: root.optString("html_url").ifBlank { RELEASES_PAGE }
        return LatestRelease(versionName = version, downloadUrl = download)
    }
}

/**
 * Compares dotted numeric versions like `1.0.42`, tolerant of a leading `v` and of
 * differing component counts (`1.0` vs `1.0.42`). Kept pure and separate so it can
 * be unit-tested without any network.
 */
object VersionCompare {

    /** Drops a leading `v`/`V` and surrounding whitespace from a tag. */
    fun strip(raw: String?): String =
        raw?.trim()?.removePrefix("v")?.removePrefix("V")?.trim().orEmpty()

    /** Splits a version into its numeric components, ignoring any non-numeric tail. */
    fun parts(raw: String?): List<Int> =
        strip(raw)
            .split('.', '-', '+')
            .map { segment -> segment.takeWhile { it.isDigit() } }
            .takeWhile { it.isNotEmpty() }
            .map { it.toInt() }

    /** True when [candidate] is a strictly higher version than [current]. */
    fun isNewer(candidate: String?, current: String?): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        if (a.isEmpty()) return false
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}

/** UI state for the "check for updates" flow on the About screen. */
sealed interface UpdateStatus {
    /** Nothing requested yet. */
    data object Idle : UpdateStatus

    /** A check is in flight. */
    data object Checking : UpdateStatus

    /** The running build is the newest published release. */
    data class UpToDate(val current: String) : UpdateStatus

    /** A newer release exists and can be installed from [downloadUrl]. */
    data class Available(val version: String, val downloadUrl: String) : UpdateStatus

    /** The check couldn't complete (offline, rate-limited, etc.). */
    data class Failed(val message: String) : UpdateStatus
}
