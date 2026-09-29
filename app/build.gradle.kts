plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hdlee73.sajeonapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hdlee73.sajeonapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
