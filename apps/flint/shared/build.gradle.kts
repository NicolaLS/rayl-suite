import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins { id("xyz.lilsus.raylsuite.app.shared") }

kotlin {
    android { namespace = "xyz.lilsus.flint.shared" }
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.withType<Framework>().configureEach {
            binaryOption("bundleId", "xyz.lilsus.flint.shared")
            export(project(":providers:spark:experience"))
            export(project(":feature:payment-hub"))
            export(project(":feature:payment-ui"))
            export(project(":feature:settings"))
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":providers:spark:experience"))
            api(project(":feature:payment-hub"))
            api(project(":feature:payment-ui"))
            api(project(":feature:settings"))
            implementation(libs.compose.runtime)
        }
    }
}
