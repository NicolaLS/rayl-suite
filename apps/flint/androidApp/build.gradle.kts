import java.util.Properties

plugins {
    id("xyz.lilsus.raylsuite.android.application")
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) file.inputStream().use(::load)
    }
val productionBreezApiKey =
    providers.gradleProperty("FLINT_BREEZ_API_KEY")
        .orElse(providers.environmentVariable("FLINT_BREEZ_API_KEY"))
        .orElse(localProperties.getProperty("FLINT_BREEZ_API_KEY") ?: "")

val validateProductionConfiguration = tasks.register("validateProductionConfiguration") {
    description = "Checks that the production Breez API key is configured."
    notCompatibleWithConfigurationCache("Reads the production API key at execution time.")
    doLast {
        check(productionBreezApiKey.get().isNotBlank()) {
            "FLINT_BREEZ_API_KEY is required for a Flint release."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateProductionConfiguration)
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "xyz.lilsus.flint"

    defaultConfig {
        applicationId = "xyz.lilsus.flint"
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["appLabel"] = "Flint"
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "BREEZ_API_KEY", "".asBuildConfigString())
            manifestPlaceholders["appLabel"] = "Flint Dev"
        }
        getByName("release") {
            buildConfigField(
                "String",
                "BREEZ_API_KEY",
                productionBreezApiKey.get().asBuildConfigString()
            )
            manifestPlaceholders["appLabel"] = "Flint"
        }
        create("e2e") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".e2e"
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["appLabel"] = "Flint E2E"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":flint:shared"))
    implementation(project(":core:ui"))
}
