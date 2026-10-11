plugins {
    id("com.android.application")
}

android {
    namespace = "dev.glucose.yt2agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.glucose.yt2agent"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // youtubedl-android reads its bundled .so files from disk; the APK must extract them.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")

    testImplementation("junit:junit:4.13.2")
    // org.json stub for local unit tests (Android provides it at runtime)
    testImplementation("org.json:json:20240303")
}
