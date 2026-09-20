# ARCH-rayl-suite: Rayl suite

The [Gradle project](../settings.gradle.kts) is a Kotlin Multiplatform suite of
Android and iOS payment apps. Product composition lives in `apps/`; wallet
behavior lives in `providers/`.

| App | Provider composition |
| --- | --- |
| Blip | Blink |
| Lasr | Nostr Wallet Connect (NWC) |
| Flint | Spark through the Breez SDK |
| Rayl | Blink and NWC experiences |

Rayl offers every integrated provider during setup, with no release availability
gates. [RaylSelection](../apps/rayl/shared/src/commonMain/kotlin/xyz/lilsus/rayl/RaylSelection.kt)
remembers one selected provider experience; it does not combine wallets into one
payment engine.

## Shared boundaries

Apps supply product configuration and platform entry points. Provider modules
own wallet connection, credentials, and payment execution, as described in
[ARCH-providers](../providers/specs/ARCH-providers.md).
The root `core/`, `feature/`, and `integration/` modules supply provider-neutral
models, platform services, reusable UI, payment-input utilities, and service
adapters. Shared payment presentation does not own settlement.

[ARCH-payment-hub](../feature/payment-hub/specs/ARCH-payment-hub.md) describes
the shared Hub UI and its optional remote catalog. `backend/hub` is a separate
JVM service; `core/hub-api` is its shared wire contract with clients.

The dependency rules in [`verifyModuleDependencies`](../build.gradle.kts)
keep apps independent, providers independent of one another, and neutral
modules independent of apps and providers. Backend modules share only
`core/hub-api` with client code; clients do not depend on backend implementation.

## Platform composition

Common Kotlin owns application state and payment logic. Android renders with
Compose; iOS renders with SwiftUI through Kotlin controllers and snapshots
exported by each app's `Shared` framework. Reusable native views live beside
their feature or provider, with app hosts connecting platform lifecycle and
incoming links to the selected experience.
