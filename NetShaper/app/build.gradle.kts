plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.netshaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.netshaper"
        minSdk = 24
        // targetSdk 33 on purpose: avoids Android 14 foreground-service-type enforcement for this
        // sideloaded QA tool. The app still runs on Android 14/15 devices.
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so `assembleRelease` yields an installable APK without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
