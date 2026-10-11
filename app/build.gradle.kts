import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "info.chanu.yt2agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "info.chanu.yt2agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0"
    }

    // Release signing lives in keystore.properties (gitignored); unsigned without it.
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
