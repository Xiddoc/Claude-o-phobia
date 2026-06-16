# Add project specific ProGuard rules here.
# Default keep rules from proguard-android-optimize.txt apply, and AndroidX /
# Compose / DataStore ship their own consumer rules.

# The ViewModel is created reflectively via its Application constructor, so keep
# that constructor intact under R8.
-keep class com.xiddoc.claudeophobia.ui.MainViewModel {
    <init>(android.app.Application);
}

# The LSPosed entry point is instantiated reflectively from assets/xposed_init,
# so nothing in our own code references it — keep it (and its no-arg ctor).
-keep class com.xiddoc.claudeophobia.xposed.** { *; }

# The Xposed/LSPosed API is compileOnly (provided by the framework at runtime),
# so its classes aren't in the APK — don't warn about the missing references.
-dontwarn de.robv.android.xposed.**
-dontwarn android.app.AndroidAppHelper
