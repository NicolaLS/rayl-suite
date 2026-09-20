plugins {
    id("xyz.lilsus.raylsuite.android.application")
}

android {
    namespace = "xyz.lilsus.blip"

    defaultConfig {
        applicationId = "xyz.lilsus.blip"
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["mainActivityName"] = "xyz.lilsus.blip.MainActivity"
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["appLabel"] = "Blip Dev"
        }
        getByName("release") {
            manifestPlaceholders["appLabel"] = "Blip"
        }
        create("e2e") {
            initWith(getByName("release"))
            applicationIdSuffix = ".e2e"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["appLabel"] = "Blip E2E"
            manifestPlaceholders["mainActivityName"] = "xyz.lilsus.blip.E2eMainActivity"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += "/fr/acinq/secp256k1/jni/native/**"
        }
    }
}

dependencies {
    implementation(project(":blip:shared"))
    implementation(project(":core:network"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.core.ktx)
}
