package xyz.lilsus.flint

import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics

@Composable
fun App(host: FlintAppHost, performanceDiagnostics: PerformanceDiagnostics? = null) {
    SparkExperience(
        host = host,
        configuration = FLINT_EXPERIENCE,
        performanceDiagnostics = performanceDiagnostics
    )
}
