plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.rayl.integration.club"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:rayl-api"))
            implementation(project(":core:network"))
            api(project(":core:settings"))
            implementation(libs.bitcoin.kmp)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
