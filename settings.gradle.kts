rootProject.name = "rayl-suite"

pluginManagement {
    includeBuild("build-logic")

    repositories {
        maven("https://mvn.breez.technology/releases")
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        maven("https://mvn.breez.technology/releases")
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

fun includeApp(name: String, modules: List<String>) {
    val appPath = ":$name"
    val allModules = listOf("shared", "androidApp") + modules

    include(allModules.map { module -> "$appPath:$module" })

    project(appPath).projectDir = file("apps/$name")

    allModules
        .flatMap { module ->
            val segments = module.split(":")
            segments.indices.map { index ->
                segments.take(index + 1).joinToString(":")
            }
        }.distinct()
        .forEach { module ->
            project("$appPath:$module").projectDir =
                file("apps/$name/${module.replace(':', '/')}")
        }
}

includeApp("blip", listOf("benchmark"))
includeApp("flint", emptyList())
includeApp("lasr", emptyList())
includeApp("rayl", emptyList())

fun includeProvider(name: String, modules: List<String>) {
    modules.forEach { module ->
        val path = ":providers:$name:$module"
        include(path)
        project(path).projectDir = file("providers/$name/${module.replace(':', '/')}")
    }
}

includeProvider(
    "blink",
    listOf(
        "experience",
        "feature:blink-contacts",
        "feature:onboarding",
        "feature:payment",
        "feature:wallet-connection",
        "feature:wallet-settings",
        "integration:blink",
        "ui"
    )
)
includeProvider(
    "nwc",
    listOf(
        "experience",
        "feature:onboarding",
        "feature:payment",
        "feature:wallet-connection",
        "feature:wallet-details",
        "integration:nwc"
    )
)
includeProvider(
    "spark",
    listOf(
        "experience",
        "application",
        "feature:onboarding",
        "feature:payment",
        "feature:wallet-connection",
        "integration:wallet"
    )
)

include(":core:model")
include(":core:hub-api")
include(":core:camera")
include(":core:network")
include(":core:payment")
include(":core:settings")
include(":core:ui")
include(":feature:theme-settings")
include(":feature:currency-settings")
include(":feature:language-settings")
include(":feature:onboarding")
include(":feature:payment-currency")
include(":feature:payment-ui")
include(":feature:settings")
include(":feature:payment-settings")
include(":feature:payment-hub")
include(":feature:app-shell")
include(":feature:wallet-management")
include(":integration:exchange-rate")
include(":integration:hub")
include(":integration:lnurl")
include(":backend:hub")
