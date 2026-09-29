plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.core.widgetspec"
    }
    // The Rayl backend validates catalog definitions with this parser.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
