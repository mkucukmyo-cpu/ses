plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sesle.visual"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sesle.visual"
        minSdk = 29            // AudioPlaybackCapture en az Android 10 ister
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Test amaçlı: debug anahtarıyla imzala, doğrudan yüklenebilsin
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
