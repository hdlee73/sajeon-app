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
        versionCode = 14
        versionName = "1.5.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
}
