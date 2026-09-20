package xyz.lilsus.flint

import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics

@Composable
fun App(
    environment: FlintEnvironment,
    breezApiKey: String? = null,
    performanceDiagnostics: PerformanceDiagnostics? = null
) {
    SparkExperience(
        environment = environment,
        breezApiKey = breezApiKey,
        configuration = FLINT_EXPERIENCE,
        performanceDiagnostics = performanceDiagnostics
    )
}
