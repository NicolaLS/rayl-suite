plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:rayl-api"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.auth)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.ktor.server.contentNegotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testRuntimeOnly(libs.embedded.postgres.binaries.darwin.arm64)
    testRuntimeOnly(libs.embedded.postgres.binaries.linux.amd64)
}
