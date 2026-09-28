plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.spark.feature.payment"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":providers:spark:application"))
            api(project(":core:model"))
            api(project(":core:payment"))
            implementation(project(":core:ui"))
            api(project(":feature:payment-hub"))
            implementation(project(":feature:currency-settings"))
            implementation(project(":feature:payment-currency"))
            implementation(project(":feature:payment-settings"))
            api(project(":feature:payment-ui"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.multiplatform.settings)
        }
    }
}
