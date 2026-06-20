# Add project specific ProGuard rules here.
# Default keep rules from proguard-android-optimize.txt apply, and AndroidX /
# Compose / DataStore ship their own consumer rules.

# The ViewModel is created reflectively via its Application constructor, so keep
# that constructor intact under R8.
-keep class com.xiddoc.claudeophobia.ui.MainViewModel {
    <init>(android.app.Application);
}

# The libxposed module entry point is instantiated reflectively from
# META-INF/xposed/java_init.list, so nothing in our own code references it. Keep
# every XposedModule subclass (and its no-arg ctor) and rewrite the list file if
# the entry class gets obfuscated.
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class com.xiddoc.claudeophobia.xposed.** { *; }

# The libxposed API is compileOnly (provided by the framework at runtime), so its
# classes aren't in the APK — don't warn about the missing references.
-dontwarn io.github.libxposed.**
