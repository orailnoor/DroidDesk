import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing: app/android/key.properties (local) or DROIDDESK_* env vars (CI).
// Every published release must use this same key, otherwise in-app updates are rejected.
val releaseKeystore = Properties().apply {
    rootProject.file("key.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
// Trimmed: a stray space after a hand-edited value would otherwise break the path or password.
fun releaseSetting(property: String, env: String): String? =
    (releaseKeystore.getProperty(property) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }
val releaseStoreFile = releaseSetting("storeFile", "DROIDDESK_KEYSTORE")

android {
    namespace = "com.orailnoor.droiddesk"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "26.3.11579264"

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.orailnoor.droiddesk"
        minSdk = 28  // Downgraded to 28 to bypass W^X (Write XOR Execute) restrictions on app data
        targetSdk = 28 // API 28 completely disables the Android 10+ execve() block
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            // ARM64 only — all modern Android phones
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseSetting("storePassword", "DROIDDESK_KEYSTORE_PASSWORD")
                keyAlias = releaseSetting("keyAlias", "DROIDDESK_KEY_ALIAS")
                keyPassword = releaseSetting("keyPassword", "DROIDDESK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without a release key (contributor builds) fall back to the debug key so the
            // APK still installs; such builds cannot update a published release in place.
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Enable native (C/C++) build support for wlroots integration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

flutter {
    source = "../.."
}
