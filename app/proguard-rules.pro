# Add project specific ProGuard rules here.
# Default keep rules from proguard-android-optimize.txt apply, and AndroidX /
# Compose / DataStore ship their own consumer rules.

# The ViewModel is created reflectively via its Application constructor, so keep
# that constructor intact under R8.
-keep class com.xiddoc.claudeophobia.ui.MainViewModel {
    <init>(android.app.Application);
}
