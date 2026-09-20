# ARCH-rayl-suite: Rayl application

The [Gradle project](../settings.gradle.kts) builds Rayl for Android and iOS.
Rayl is the only distributed app. Product composition lives in `apps/rayl/`;
independent Blink, Nostr Wallet Connect (NWC), and Spark implementations live in
`providers/`.

Rayl offers every integrated provider during setup, with no release availability
gates. [RaylSelection](../apps/rayl/shared/src/commonMain/kotlin/xyz/lilsus/rayl/RaylSelection.kt)
remembers one selected provider experience; it does not combine wallets into one
payment engine.

## Shared boundaries

Rayl supplies product configuration and platform entry points. Provider modules
own wallet connection, credentials, and payment execution, as described in
[ARCH-providers](../providers/specs/ARCH-providers.md).
The root `core/`, `feature/`, and `integration/` modules supply provider-neutral
models, platform services, reusable UI, payment-input utilities, and service
adapters. Shared payment presentation does not own settlement.

[ARCH-payment-hub](../feature/payment-hub/specs/ARCH-payment-hub.md) describes
the shared Hub UI and its optional remote catalog. `backend/hub` is a separate
JVM service; `core/hub-api` is its shared wire contract with clients.

The dependency rules in [`verifyModuleDependencies`](../build.gradle.kts)
keep providers independent of one another and of the app, and neutral modules
independent of the app and providers. Backend modules share only
`core/hub-api` with client code; clients do not depend on backend implementation.

## Platform composition

Common Kotlin owns application state and payment logic. Android renders with
Compose; iOS renders with SwiftUI through Kotlin controllers and snapshots
exported by Rayl's `Shared` framework. Reusable native views live beside
their feature or provider, with Rayl connecting platform lifecycle and
incoming links to the selected experience.
