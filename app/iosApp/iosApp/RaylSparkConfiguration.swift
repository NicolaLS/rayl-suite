import Foundation
import Shared

/// Debug and E2E builds use the staging backend and Spark's debug environment; releases use production.
func configureRaylEnvironment() {
#if RAYL_DEBUG && RAYL_PRODUCTION
#error("Rayl environment conditions are contradictory")
#elseif RAYL_DEBUG
    RaylIosApp.shared.configureBackend(backend: RaylBackend.staging)
    RaylIosApp.shared.configureSpark(environment: SparkEnvironment.debug, breezApiKey: nil)
#elseif RAYL_PRODUCTION
    RaylIosApp.shared.configureBackend(backend: RaylBackend.production)
    RaylIosApp.shared.configureSpark(
        environment: SparkEnvironment.production,
        breezApiKey: (Bundle.main.object(forInfoDictionaryKey: "RaylBreezAPIKey") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    )
#else
#error("A Rayl environment compilation condition is required")
#endif
}
