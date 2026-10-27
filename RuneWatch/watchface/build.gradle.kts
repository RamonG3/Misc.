plugins {
    id("com.android.application")
}

// CI passes the run number so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.ramong.runewatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ramong.runewatch"
        // Watch Face Format v1 needs Wear OS 4 (API 33) or newer.
        minSdk = 33
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Same throwaway key as RuneLauncher, committed on purpose so every CI build can be
        // installed over the last one. Fine for sideloading, not for the Play Store.
        create("shared") {
            storeFile = rootProject.file("keystore/shared.jks")
            storePassword = "runelauncher"
            keyAlias = "runelauncher"
            keyPassword = "runelauncher"
        }
    }

    buildTypes {
        // R8 strips the generated R classes, so the APK ships with no classes.dex at all:
        // Watch Face Format faces must not contain code.
        debug {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            // Resources are only referenced from res/raw/watchface.xml, which the shrinker
            // cannot see, so it would delete all of them.
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = false
    }
}
