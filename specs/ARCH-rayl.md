# ARCH-rayl: Rayl application

The [Gradle project](../settings.gradle.kts) builds Rayl for Android and iOS.
Rayl is the only distributed app. Product composition lives in `app/`;
independent Rayl, Blink, Nostr Wallet Connect (NWC), and Spark implementations
live in `providers/`; only the Rayl provider needs no credentials.

Rayl offers every integrated provider during setup, with no release availability
gates. [RaylSelection](../app/shared/src/commonMain/kotlin/xyz/lilsus/rayl/RaylSelection.kt)
keeps setup selection transient and remembers only the configured provider; it
does not combine wallets into one payment engine. Before mounting an experience,
the platform host clears unowned provider storage. Returning to the chooser also
requires cleanup, as defined by [SPEC-wallet-lifecycle](SPEC-wallet-lifecycle.md).

## Shared boundaries

Rayl supplies product configuration and platform entry points. Provider modules
own wallet connection, credentials, and payment execution, as described in
[ARCH-providers](../providers/specs/ARCH-providers.md).
The root `core/`, `feature/`, and `integration/` modules supply provider-neutral
models, platform services, reusable UI, payment-input utilities, and service
adapters. Shared payment presentation does not own settlement.

[ARCH-payment-hub](../feature/payment-hub/specs/ARCH-payment-hub.md) describes
the shared Hub UI.

`backend/rayl` is the Rayl backend, deployed on Railway. It serves bitcoin
prices aggregated from several public sources, and every provider reads fiat
prices from it through `integration/exchange-rate`. `core/rayl-api` is its wire
contract and holds the production base URL.
The same service hosts Rayl Club (`backend/club`) when its Postgres database and Supabase project
are configured. Supabase only authenticates members; the backend verifies their tokens against
the project's public keys and is the only reader and writer of Club data. Club funds sit in a
treasury Spark wallet that the backend runs through the Breez SDK; it receives membership fees, and
an append-only double-entry ledger in the Club database records every movement.

The dependency rules in [`verifyModuleDependencies`](../build.gradle.kts)
keep providers independent of one another and of the app, and neutral modules
independent of the app and providers. Backend modules share only their wire
contracts (`core/rayl-api`) with client code; clients do not
depend on backend implementation.

## Platform composition

Common Kotlin owns application state and payment logic. Android renders with
Compose; iOS renders with SwiftUI through Kotlin controllers and snapshots
exported by Rayl's `Shared` framework. Reusable native views live beside
their feature or provider, with Rayl connecting platform lifecycle and
incoming links to the selected experience.
