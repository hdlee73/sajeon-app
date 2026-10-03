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
        versionCode = 32
        versionName = "1.6.8"
    }

    // Every release must be signed with the same permanent key, otherwise Android refuses to install an
    // update over the existing app ("App not installed"). CI passes the restored keystore explicitly instead
    // of relying on the default ~/.android/debug.keystore location.
    val releaseKeystore = System.getenv("SAJEON_KEYSTORE")
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs {
            create("fixed") {
                storeFile = file(releaseKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
}

