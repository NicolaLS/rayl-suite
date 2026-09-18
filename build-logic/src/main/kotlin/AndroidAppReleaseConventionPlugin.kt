import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/** Signs Google Play bundles with the suite's upload key. */
class AndroidAppReleaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin("com.android.application") {
            target.configureUploadSigning()
        }
    }
}

private fun Project.configureUploadSigning() {
    val upload = ReleaseSigningIdentity(providers)
    extensions.configure<ApplicationExtension> {
        val signing = signingConfigs.create("upload")
        signing.storeFile = upload.keystore
        signing.storePassword = upload.resolveStorePassword()
        signing.keyAlias = upload.keyAlias.orNull
        signing.keyPassword = upload.resolveKeyPassword()
        buildTypes.getByName("release").signingConfig = signing
    }

    val validate = tasks.register("validateUploadSigning") {
        group = "verification"
        description = "Checks that the Google Play upload signing credentials are configured."
        notCompatibleWithConfigurationCache("Reads local signing credentials at execution time.")
        doLast {
            val missing = upload.missingVariables()
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Configure upload signing using .envrc.example. Missing:\n" +
                        missing.joinToString("\n") { "  $it" }
                )
            }
        }
    }

    // Fail before the app's release compilation; Debug and E2E builds need no upload key.
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        dependsOn(validate)
    }

    val bundle = objects.fileProperty()
    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants(selector().withBuildType("release")) { variant ->
            bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
        }
    }
    tasks.matching { it.name == "bundleRelease" }.configureEach {
        dependsOn(validate)
        doLast { logger.lifecycle("Google Play bundle: ${bundle.get().asFile}") }
    }
}
