plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.blink.feature.onboarding"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(project(":providers:blink:feature:wallet-connection"))
            api(project(":providers:blink:integration:blink"))
            implementation(project(":core:ui"))
            api(project(":feature:onboarding"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies {
            implementation(project(":core:ui"))
        }
    }
}
