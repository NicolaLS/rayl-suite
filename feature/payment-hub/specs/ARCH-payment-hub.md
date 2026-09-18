# ARCH-payment-hub: Shared payment Hub

The Hub is a provider-neutral feature in
[ARCH-rayl-suite](../../../specs/ARCH-rayl-suite.md). Common Kotlin owns its saved
state and behavior, with Compose and SwiftUI presentations.

## Local state and payment handoff

Contacts, payment actions, and placed widgets have separate identities.
`DefaultPaymentHubRepository` persists them in app-scoped settings;
`WidgetHubViewModel` derives the gallery and displayed tiles from that state.

[`PaymentHubController`](../src/commonMain/kotlin/xyz/lilsus/raylsuite/feature/paymenthub/host/PaymentHubController.kt)
emits payment intents to the
[provider coordinators](../../../providers/specs/ARCH-providers.md).
Providers own execution and report successful target payments back to update
Hub usage history. The Hub does not submit wallet payments itself.

## Remote catalog boundary

The optional remote path uses [`integration/hub`](../../../integration/hub)
and the versioned wire types in [`core/hub-api`](../../../core/hub-api).
Remote descriptors and content are validated data for compiled native metric
widgets, rather than downloaded executable UI.

The [current build configuration](../build.gradle.kts) generates an empty
backend URL, so Android and iOS Hub surfaces create no remote session.
The separate [`backend/hub`](../../../backend/hub) JVM Ktor service currently
returns an empty catalog and unavailable widget content; it has no role in
wallet payment execution.
