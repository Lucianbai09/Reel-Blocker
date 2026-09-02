plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reelblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reelblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// No dependencies block: kotlin-stdlib comes from the Kotlin plugin, and nothing
// else is used. Keeping it that way is what makes the APK and the sync fast.
