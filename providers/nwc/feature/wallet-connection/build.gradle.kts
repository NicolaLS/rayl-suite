plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.nwc.feature.walletconnection"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":providers:nwc:integration:nwc"))
            implementation(project(":core:camera"))
            implementation(project(":core:ui"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
    }
}
