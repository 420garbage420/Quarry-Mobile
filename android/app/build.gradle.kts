plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.quarry.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.quarry.app"
        minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Optional on-device OCR for scanned PDFs / images (Play Services ML Kit)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
