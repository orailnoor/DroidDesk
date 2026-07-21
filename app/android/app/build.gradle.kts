import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Optional release signing. Create android/key.properties (git-ignored) with
// storeFile / storePassword / keyAlias / keyPassword to sign release builds with
// a real upload key. If absent, release falls back to the debug key so
// GitHub-distributed test APKs stay directly installable.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists().also {
    if (it) keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

android {
    namespace = "com.orailnoor.droiddesk"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        aidl = true
        buildConfig = true
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
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Use a real release key when key.properties is present; otherwise
            // fall back to the debug key so GitHub-distributed test APKs remain
            // directly installable.
            signingConfig = if (hasReleaseKeystore) {
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
