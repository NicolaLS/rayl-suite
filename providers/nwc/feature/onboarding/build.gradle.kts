plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.nwc.feature.onboarding"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:camera"))
            implementation(project(":core:ui"))
            implementation(project(":feature:onboarding"))
            implementation(project(":providers:nwc:feature:wallet-connection"))
            implementation(project(":providers:nwc:integration:nwc"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            api(project(":core:ui"))
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.navigation.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
