plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.core.model"
    }
    // The Rayl backend validates widget definitions with the same code.
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
