plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.nwc.feature.payment"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:payment"))
            api(project(":providers:nwc:integration:nwc"))
            implementation(project(":core:ui"))
            api(project(":feature:payment-hub"))
            implementation(project(":feature:currency-settings"))
            implementation(project(":feature:payment-currency"))
            implementation(project(":feature:payment-settings"))
            api(project(":feature:payment-ui"))
            implementation(libs.bitcoin.kmp)
            api(libs.lightning.kmp.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.secp256k1.kmp.jni.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
