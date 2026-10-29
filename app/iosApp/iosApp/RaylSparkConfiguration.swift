import Foundation
import Shared

func configureRaylSpark() {
#if DEBUG
    RaylEarlyStartKt.enableRaylTimings()
#endif
#if RAYL_DEBUG && RAYL_PRODUCTION
#error("Rayl environment conditions are contradictory")
#elseif RAYL_DEBUG
    RaylIosApp.shared.configureSpark(environment: SparkEnvironment.debug, breezApiKey: nil)
#elseif RAYL_PRODUCTION
    RaylIosApp.shared.configureSpark(
        environment: SparkEnvironment.production,
        breezApiKey: (Bundle.main.object(forInfoDictionaryKey: "RaylBreezAPIKey") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    )
#else
#error("A Rayl environment compilation condition is required")
#endif
}
