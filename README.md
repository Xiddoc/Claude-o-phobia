# Claude-o-phobia ⏳

<p align="center"><em>An elegant Android app that counts down to your weekly Claude limit reset — because waiting for that fresh week of usage is the best kind of anticipation. 🧡</em></p>

## Features

- **🕑 Live countdown** to the next weekly reset, inside a ring that fills up as
  the week elapses.
- **🔋 Percentage of the week elapsed**, so you know roughly where a steady,
  evenly-paced usage level *should* be right now.
- **📊 Live usage** stats which show how much of your **weekly limit** you've used vs. the expected pace.
- **🖥 A home-screen widget** — a glance-able card with the weekly percentage and
  a glowing progress bar.

## Live usage via LSPosed

The app doubles as an [LSPosed](https://github.com/JingMatrix/LSPosed) module. Rather
than using root to read the Claude app's files from the outside, the module runs
*inside* the Claude process — where it owns those files outright — reads your
session, calls the same `claude.ai/.../usage` endpoint the app itself calls, and
hands the resulting **percentages** back to Claude-o-phobia through a small
`ContentProvider`. Your session cookie never leaves the Claude process; only the
non-secret usage figures cross over.

To enable it:

1. Install Claude-o-phobia on a device with LSPosed (Zygisk/Magisk or KernelSU).
2. In the LSPosed manager, enable the **Claude-o-phobia** module and tick both
   **Claude-o-phobia** and **Claude** in its scope.
3. Reboot, then open the Claude app once so the module can capture your usage.
4. Flip on *Live usage via LSPosed* in the app's settings.

## Installing

Grab the latest APK from [the Releases tab](https://github.com/Xiddoc/Claude-o-phobia/releases), and install it.

## Building

Requires the Android SDK (compileSdk 35) and JDK 17.

```bash
./gradlew assembleDebug      # build the debug APK
./gradlew testDebugUnitTest  # run unit tests
```

The APK lands in `app/build/outputs/apk/debug/`.

---

Made with care (and Claude). Thanks for everything. 🧡
