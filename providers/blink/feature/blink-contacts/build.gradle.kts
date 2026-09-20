plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.blink.feature.blinkcontacts"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":providers:blink:integration:blink"))
            api(project(":feature:payment-hub"))
            implementation(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }
    }
}
