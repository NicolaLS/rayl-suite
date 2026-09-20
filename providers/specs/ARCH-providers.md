# ARCH-providers: Wallet provider ownership

Within [ARCH-rayl-suite](../../specs/ARCH-rayl-suite.md), each provider owns its
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
presentations; Flint supplies product configuration and platform entry points.

## Shared services

Blink and NWC resolve LNURL through `integration/lnurl` and the shared
`LnurlInvoiceResolver`. Spark extends the shared input parser for Spark invoices
and delegates LNURL preparation to Breez.

The [shared Hub](../../feature/payment-hub/specs/ARCH-payment-hub.md) emits
payment requests to provider coordinators. Providers execute them and report
successful target payments back to the Hub.
