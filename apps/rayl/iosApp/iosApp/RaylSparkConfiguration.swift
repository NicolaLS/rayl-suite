import Foundation
import Shared

func configureRaylSpark() {
#if RAYL_DEBUG && RAYL_PRODUCTION
#error("Rayl environment conditions are contradictory")
#elseif RAYL_DEBUG
    RaylIosApp.shared.configureSpark(environment: FlintEnvironment.debug, breezApiKey: nil)
#elseif RAYL_PRODUCTION
    RaylIosApp.shared.configureSpark(
        environment: FlintEnvironment.production,
        breezApiKey: (Bundle.main.object(forInfoDictionaryKey: "RaylBreezAPIKey") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    )
#else
#error("A Rayl environment compilation condition is required")
#endif
}
