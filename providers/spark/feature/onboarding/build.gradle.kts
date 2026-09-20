plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.spark.feature.onboarding"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":providers:spark:application"))
            implementation(project(":providers:spark:feature:wallet-connection"))
            implementation(project(":core:camera"))
            implementation(project(":core:ui"))
            implementation(project(":feature:onboarding"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.navigation.compose)
        }
    }
}
