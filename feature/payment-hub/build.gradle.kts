plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.feature.paymenthub"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:ui"))
            api(project(":integration:hub"))
            implementation(project(":core:network"))
            implementation(libs.compose.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.navigation.event.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Rayl 1.0 ships the local Hub only. External URL overrides cannot enable it.
val hubBackendUrl = providers.provider { "" }
val hubConfigurationDirectory = layout.buildDirectory.dir("generated/hubConfiguration/kotlin")
val generateHubConfiguration = tasks.register("generateHubConfiguration") {
    inputs.property("backendUrl", hubBackendUrl)
    outputs.dir(hubConfigurationDirectory)
    doLast {
        val escaped = hubBackendUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r")
        val file = hubConfigurationDirectory.get().file(
            "xyz/lilsus/raylsuite/feature/paymenthub/HubBackendConfiguration.kt"
        ).asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package xyz.lilsus.raylsuite.feature.paymenthub\n\n" +
                "internal object HubBackendConfiguration {\n" +
                "    const val baseUrl: String = \"$escaped\"\n" +
                "}\n"
        )
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(hubConfigurationDirectory) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateHubConfiguration)
}
tasks.matching { it.name.contains("Ktlint", ignoreCase = true) }.configureEach {
    dependsOn(generateHubConfiguration)
}
