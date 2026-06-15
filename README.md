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

The Claude Android app does **not** publish a documented, stable on-device
format for usage / rate-limit state. So the root reader is deliberately
**best-effort**:

1. It uses `su` to grep the Claude app's private storage
   (`/data/data/<package>`) for JSON that looks like rate-limit data.
2. It heuristically matches keys that resemble utilization percentages and
   reset timestamps (see the `*_HINTS` regexes in
   [`RootUsageReader.kt`](app/src/main/java/com/xiddoc/claudeophobia/data/RootUsageReader.kt)).

If Anthropic changes how the app stores this data, some live values may go
missing — the countdown and week-percentage features keep working regardless.
You can:

- Change the **Claude package name** in Settings (default
  `com.anthropic.claude`).
- Tune the `WEEKLY_HINTS` / `FIVE_HOUR_HINTS` / `USAGE_HINTS` / `RESET_HINTS`
  regexes if you inspect your own app data and find the real key names.

Root reading is **off by default** and only ever reads — it never writes to
the Claude app.

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
