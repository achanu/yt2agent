import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "info.chanu.yt2agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "info.chanu.yt2agent"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "0.10.0"

        ndk {
            // x86/x86_64 only serve emulators; splits.include does not filter the
            // universal APK, this does.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // One APK per ABI plus a universal APK; modern phones download the small one.
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = true
            }
        }
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
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.exists()) signingConfig = signingConfigs.getByName("release")
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
    // FileProvider: sharing a file requires a content:// URI since API 24.
    implementation("androidx.core:core:1.16.0")

    testImplementation("junit:junit:4.13.2")
    // org.json stub for local unit tests (Android provides it at runtime)
    testImplementation("org.json:json:20240303")
}
