plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourbiz.loyverseapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yourbiz.loyverseapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Sign every build with the same key, so a new version installs
    // straight over the old one and keeps its saved token and pools link.
    // The key file is written by the GitHub build from the KEYSTORE_BASE64
    // secret. Without it, the build still works but uses a throwaway key.
    signingConfigs {
        getByName("debug") {
            val fixedKey = rootProject.file("signing/debug.keystore")
            if (fixedKey.exists()) {
                storeFile = fixedKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
