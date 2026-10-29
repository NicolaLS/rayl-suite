plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    alias(libs.plugins.breezSpark)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.rayl.integration.pocket"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:settings"))
            implementation(libs.bitcoin.kmp)
            implementation(libs.breez.sdk.spark)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
