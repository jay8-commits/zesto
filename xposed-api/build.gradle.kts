plugins {
    alias(libs.plugins.android.application) apply false
    id("com.android.library")
}

android {
    namespace = "de.robv.android.xposed"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
