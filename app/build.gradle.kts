plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freecrane.adrenotest"
    compileSdk = 34

    defaultConfig {
        // The build the Actions tab gives you keeps the old id (com.freecrane.adrenotest); the release build passes -PappId=com.freecrane.epinephro
        applicationId = (project.findProperty("appId") as String?) ?: "com.freecrane.adrenotest"
        manifestPlaceholders["appLabel"] = (project.findProperty("appLabel") as String?) ?: "Epinephro Dev"
        minSdk = 26
        targetSdk = 28          // low on purpose: keeps native-library loading permissive
        versionCode = 4
        versionName = "1.0-beta1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Same key on every build, so new APKs install over old ones.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        getByName("debug") {
            // Debuggable apps get Android's strict JNI checker, which kills many games.
            isDebuggable = false
        }
    }

    // libadrenotools hook libs must exist as real files on disk
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
