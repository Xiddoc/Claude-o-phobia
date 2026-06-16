# Claude-o-phobia ⏳

A small, elegant, dark-themed Android app that counts down to your weekly
Claude limit reset — because waiting for that fresh week of usage is the best
kind of anticipation. 🧡

<p align="center"><em>Thursday 08:00 UTC, here we come.</em></p>

## What it shows

- **Live countdown** to the next weekly reset, inside a ring that fills up as
  the week elapses.
- **Percentage of the week elapsed**, so you know roughly where a steady,
  evenly-paced usage level *should* be right now.
- **Settings** (the gear icon, top-right) to configure your reset **day of
  week**, **time**, and **time zone**. Defaults to **Thursday 08:00 UTC**.
- **Live usage via root (optional)** — if your phone is rooted, the app can
  read your actual Claude usage and show:
  - how much of your **weekly limit** you've used (vs. the expected pace), and
  - how far along your **5-hour rolling window** is, including when it ends.

## The root feature (read me)

The Claude app doesn't cache usage on disk — it asks the server for it. So this
app does the same thing the official client does, just on your behalf:

1. It uses `su` to read the Claude app's **cookie jar**
   (`shared_prefs/user_cookies_*.xml`) and pull out the `sessionKey` cookie
   (plus your `lastActiveOrg`), see
   [`RootUsageReader.kt`](app/src/main/java/com/xiddoc/claudeophobia/data/RootUsageReader.kt).
2. It then calls
   `GET https://claude.ai/api/organizations/{org}/usage`, forwarding those
   cookies, and parses the JSON (`seven_day.utilization`,
   `five_hour.utilization`, `resets_at`, …) — see
   [`ClaudeApi.kt`](app/src/main/java/com/xiddoc/claudeophobia/data/ClaudeApi.kt).

From that you get your real weekly utilization (compared against the expected
pace), the 5-hour rolling window's progress and end time, and the **actual**
weekly reset moment — with a one-tap "sync countdown to this" button so the
timer matches reality.

**Privacy:** your session cookie and org id are read into memory only for the
duration of the request. They are **never** logged, cached, or written
anywhere by this app. Root access is only ever used to *read* the cookie jar.

The feature is **off by default**. If it can't find the cookie, you can change
the **Claude package name** in Settings (default `com.anthropic.claude`). If
Claude rejects the request (e.g. an expired session or Cloudflare), the app
tells you the HTTP status so you can retry after opening the Claude app.

## Building

Requires the Android SDK (compileSdk 35) and JDK 17.

```bash
./gradlew assembleDebug      # build the debug APK
./gradlew testDebugUnitTest  # run unit tests
```

The APK lands in `app/build/outputs/apk/debug/`.

## CI

[`.github/workflows/android.yml`](.github/workflows/android.yml) runs the unit
tests and builds the debug APK on every push and pull request, uploading the
APK as a build artifact.

## Tech

- Kotlin + Jetpack Compose (Material 3)
- A warm, Claude-inspired palette on a near-black canvas
- DataStore for settings, `java.time` for all the schedule math
- Single-activity, ViewModel + StateFlow, live 1 Hz clock tick

---

Made with care. Thanks for everything, Claude. 🧡
