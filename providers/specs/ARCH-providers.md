# ARCH-providers: Wallet provider ownership

Within [ARCH-rayl](../../specs/ARCH-rayl.md), each provider owns its
wallet connection and payment execution. Shared `core/payment` utilities handle
payment input; `feature/payment-ui` supplies presentation models, user intents,
and platform UI. Provider payment features translate their own state into that
shared presentation.

## Composition and execution

Blink and NWC assemble their runtimes in their `experience` modules. Each
provider's `feature/payment` coordinator owns admission, confirmation, session
history, and presentation, and submits payments through its own integration.
[Blink](../blink/integration/blink) wraps a GraphQL API and BTC or USD funding
wallets; [NWC](../nwc/integration/nwc) wraps the Nostr Wallet Connect client.
Their integrations own connection credentials.

Spark defines wallet and payment interfaces in
[`application`](../spark/application): `WalletAccess` controls wallet/session
access and `PaymentEngine` controls preparation and submission. Its payment
feature consumes `PaymentEngine`; [`integration/wallet`](../spark/integration/wallet)
provides platform credential/storage implementations and the Breez Spark adapter.
Spark's [`experience`](../spark/experience) assembles these parts and their platform
presentations; Rayl supplies product configuration and platform entry points.
The experience owns its wallet session lifetime. Disposal stops operations and
releases SDK resources while preserving credentials. The suspending `close()` API
reports cleanup failures and permits retry; non-suspending platform teardown is
best effort.
Successful wallet removal is reported separately from an initially empty wallet,
so hosts return to provider selection only after removal has completed.

## Native UI ownership

Provider-owned Swift code uses its own provider and provider-neutral shared
types and views. It does not depend on another provider or on Rayl's app code.
Shared Swift code remains provider-neutral. Rayl assembles the provider and
shared sources in its Xcode targets and connects them to the selected experience.
[`verifyModuleDependencies`](../../build.gradle.kts) enforces Kotlin module
dependency boundaries; Swift ownership is checked in code review because these
sources compile together in the app target.

## Shared services

Blink and NWC resolve LNURL through `integration/lnurl` and the shared
`LnurlInvoiceResolver`. Spark extends the shared input parser for Spark invoices
and delegates LNURL preparation to Breez.

The [shared Hub](../../feature/payment-hub/specs/ARCH-payment-hub.md) emits
payment requests to provider coordinators. Providers execute them and report
successful target payments back to the Hub.
