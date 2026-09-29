plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.integration.widget"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:widget-spec"))
            api(project(":core:rayl-api"))
            implementation(project(":core:network"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
    }
}
