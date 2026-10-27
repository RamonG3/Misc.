plugins {
    id("com.android.application")
}

// CI passes the run number so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.ramong.duelcards"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ramong.duelcards"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // A throwaway key checked into the repo so every CI build is signed the same way
        // and can be installed over the last one. Fine for sideloading, not for the Play Store.
        create("shared") {
            storeFile = rootProject.file("keystore/shared.jks")
            storePassword = "duelcards"
            keyAlias = "duelcards"
            keyPassword = "duelcards"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}
