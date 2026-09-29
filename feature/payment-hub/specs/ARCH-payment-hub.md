# ARCH-payment-hub: Shared payment Hub

The Hub is a provider-neutral feature in
[ARCH-rayl](../../../specs/ARCH-rayl.md). Common Kotlin owns its saved
state and behavior, with Compose and SwiftUI presentations. Built-in widget
definitions are compiled into the app; provider widgets come from rayl-widget
definitions, either from Rayl's service catalog or added by link.

## Service catalog

[`RaylServices`](../src/commonMain/kotlin/xyz/lilsus/raylsuite/feature/paymenthub/RaylServices.kt)
reads the catalog from the Rayl backend (`backend/rayl`) and keeps the last one
on the device. Listed entries are the reviewed services: only they keep their
brand and lose the "not reviewed" warning, and the Add widget sheet shows them
from the backend's validated copy of their definition without contacting the
provider. The catalog's block list disables matching widgets, including ones
added by link, and widget checkout refreshes a catalog older than five minutes
before paying, so a block reaches payments within minutes. When the
backend is unreachable the last catalog applies. With the user's consent the
Hub reports anonymous added, removed and paid counts for listed services only.

## Local state and payment handoff

Contacts, payment actions, and placed widgets have separate identities.
`DefaultPaymentHubRepository` persists them in app-scoped settings;
`WidgetHubViewModel` derives the gallery and displayed tiles from that state.

[`PaymentHubController`](../src/commonMain/kotlin/xyz/lilsus/raylsuite/feature/paymenthub/host/PaymentHubController.kt)
emits payment intents to the
[provider coordinators](../../../providers/specs/ARCH-providers.md).
Providers own execution and report successful target payments back to update
Hub usage history. The Hub does not submit wallet payments itself.
