import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

/** Target and framework configuration for Rayl's KMP composition root. */
class AppSharedConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("xyz.lilsus.raylsuite.kmp.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            extensions.configure<KotlinMultiplatformExtension> {
                targets.withType<KotlinNativeTarget>().configureEach {
                    binaries.framework {
                        baseName = "Shared"
                        isStatic = true
                        if (buildType == NativeBuildType.RELEASE) {
                            freeCompilerArgs +=
                                "-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase"
                        }
                    }
                }
            }
        }
    }
}
