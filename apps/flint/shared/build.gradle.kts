import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("xyz.lilsus.raylsuite.app.shared")
}

kotlin {
    android {
        namespace = "xyz.lilsus.flint.shared"
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.withType<Framework>().configureEach {
            binaryOption("bundleId", "xyz.lilsus.flint.shared")
            export(project(":feature:payment-hub"))
            export(project(":feature:payment-ui"))
            export(project(":feature:settings"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":providers:spark:application"))
            implementation(project(":providers:spark:feature:onboarding"))
            implementation(project(":providers:spark:feature:payment"))
            implementation(project(":providers:spark:feature:wallet-connection"))
            implementation(project(":providers:spark:integration:wallet"))
            implementation(project(":core:model"))
            implementation(project(":core:settings"))
            implementation(project(":core:ui"))
            implementation(project(":feature:currency-settings"))
            implementation(project(":feature:onboarding"))
            implementation(project(":feature:language-settings"))
            implementation(project(":feature:payment-settings"))
            api(project(":feature:payment-ui"))
            implementation(project(":feature:app-shell"))
            api(project(":feature:payment-hub"))
            api(project(":feature:settings"))
            implementation(project(":feature:theme-settings"))
            implementation(project(":integration:exchange-rate"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.navigation.compose)
            implementation(libs.navigation.event.compose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(project(":feature:wallet-management"))
        }
        iosMain.dependencies {
            implementation(project(":feature:wallet-management"))
        }
    }
}
