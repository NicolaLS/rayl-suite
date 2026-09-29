plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.feature.paymentflow"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:payment"))
            api(project(":core:ui"))
            api(project(":feature:payment-hub"))
            api(project(":feature:currency-settings"))
            api(project(":feature:payment-currency"))
            api(project(":feature:payment-settings"))
            api(project(":feature:payment-ui"))
            api(project(":core:widget-spec"))
            implementation(project(":integration:widget"))
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
