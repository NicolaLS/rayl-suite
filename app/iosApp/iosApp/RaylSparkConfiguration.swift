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
    let breezApiKey = (Bundle.main.object(forInfoDictionaryKey: "RaylBreezAPIKey") as? String)?
        .trimmingCharacters(in: .whitespacesAndNewlines)
#if DEBUG
    // The Mainnet debug app: fail loudly instead of a pocket that can never connect.
    if breezApiKey?.isEmpty != false {
        fatalError("Set RAYL_BREEZ_API_KEY in your environment (direnv) and rebuild")
    }
#endif
    RaylIosApp.shared.configureSpark(
        environment: SparkEnvironment.production,
        breezApiKey: breezApiKey
    )
#else
#error("A Rayl environment compilation condition is required")
#endif
}
