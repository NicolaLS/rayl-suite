plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    alias(libs.plugins.breezSpark)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.spark.integration.wallet"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":providers:spark:application"))
            implementation(project(":core:payment"))
            implementation(libs.breez.sdk.spark)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
    }
}
