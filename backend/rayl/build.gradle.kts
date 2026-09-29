plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("xyz.lilsus.raylsuite.backend.rayl.RaylServerKt")
}

tasks.named<JavaExec>("run") {
    environment("RAYL_ENV", "staging")
}

dependencies {
    implementation(project(":core:rayl-api"))
    implementation(project(":core:widget-spec"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.postgresql)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.testHost)
}
