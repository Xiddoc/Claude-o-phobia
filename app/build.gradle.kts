plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Version is injected by CI so the installed build matches its GitHub release
// tag (`v1.0.<run>`); the About screen compares the two to detect updates. Local
// builds fall back to a 0-run dev version. Override with
// `-PappVersionCode=<n> -PappVersionName=1.0.<n>`.
val ciVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val ciVersionName = (project.findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() }
    ?: "1.0.0"

android {
    namespace = "com.xiddoc.claudeophobia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiddoc.claudeophobia"
        // Android 14+ only: the LSPosed visibility bridge hooks system-server
        // internals, so we focus on modern, well-understood platform versions.
        minSdk = 34
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sign with the debug key so the optimized APK is installable as-is
            // on a personal device. Swap in a real keystore for distribution.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    // Xposed/LSPosed API: provided by the framework at runtime, so it's
    // compileOnly and never packaged into our APK.
    compileOnly(libs.xposed.api)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.ui.tooling)
}
