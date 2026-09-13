# Rayl architecture and behavior

Rayl is the suite's default Android/iOS product. It connects to exactly one wallet
at a time. Version 1.0 offers Blink only; NWC composition remains for future
releases. Blip, Lasr, and Flint remain independent single-provider products.
Spark is not a Rayl dependency.

See [LNURL-pay behavior](lnurl-pay.md) for endpoint restrictions, invoice
validation, and how recipient details interact with Auto Pay.

All apps are prerelease. They do not import another app's credentials, preferences,
databases, or installation state.

## Provider ownership

| Modules | Behavior | Consumers |
| --- | --- | --- |
| `providers/blink` | Blink integration, features, localized UI, native experience | Blip and Rayl |
| `providers/nwc` | NWC integration, features, native experience | Lasr; retained for future Rayl releases |
| `providers/spark` | Spark application contracts, integration, features | Flint |
| `apps/*` | Identity, storage scopes, legal links, entry points, composition | Owning app |
| Root `core/*`, `feature/*`, `integration/*` | Provider-neutral values and reusable implementation | Applicable consumers |

Each provider owns payment validation, authorization, errors, uncertain outcomes,
and foreground completion checks. Rayl selects a concrete native experience; it
does not combine providers into one payment state machine or universal wallet API.
Apps do not depend on one another, providers do not depend on other providers or
apps, and neutral modules do not depend on either. `verifyModuleDependencies`
enforces these boundaries.

## Onboarding and navigation

First use is welcome → Blink connection → app information → payment preferences
→ acknowledgement → Scan. With one available provider, Rayl proceeds directly
to its connection screen. Both a connection and completed onboarding are required
before showing the main tabs or accepting payment links. A fully set-up app opens
directly into its provider experience:

- Blink exposes Scan, Hub, and Settings, with session payments opened from Scan.
  It retains funding-wallet selection, automatic contact import after connection,
  and fee presentation.
- The retained NWC experience exposes Scan, Recent, Hub, and Settings in Lasr,
  with its own discovery, connection details, and payment completion behavior. It
  is unavailable in Rayl 1.0.

Rayl and Blip save their current onboarding stage independently of the wallet
connection. Restarting after connecting resumes the unfinished information or
configuration step; it does not require another key or mark onboarding complete.
Payment preference changes are saved as they are made. Draft credentials remain
transient until validated and saved by the connection flow.

App education and acknowledgement happen once per installation. Removing a
wallet preserves completed onboarding and general preferences. Rayl returns to
connection setup directly while only Blink is available; multiple available
providers would offer wallet choice. Blip and Lasr return directly to their own
provider setup. Blink's connection screen always offers its dashboard instructions,
including when reconnecting. Once connected again, completed users enter Scan
without repeating education or configuration. Camera permission and recovery
belong to Scan and do not affect onboarding completion.

Contacts and Payment Hub widgets are local app features. The native widget
gallery offers Contacts in single, row, and card variants, a single configured
payment Shortcut, and row/card Favorites and Recents computed from successful
Hub payment actions. Widgets reference contacts and reusable payment actions;
multiple widgets can show the same action without duplicating its history.

The post-payment save prompt saves both the contact and a single Contacts
widget. A new Blink connection automatically imports valid Blink contacts
without placing widgets or delaying successful onboarding. Removing a widget
keeps its contacts and history. Deleting a contact asks for confirmation
and removes its payment actions, dependent Shortcuts, and Contacts memberships;
empty Contacts widgets are removed. Contacts, actions, and the ordered widget
layout share one app-scoped document and are saved together.

The optional backend catalogue is initially empty. Local widgets remain usable
without a configured backend or network connection. The client understands a
bounded native metric contract; service purchasing and supplier fulfillment are
future work. See [Payment Hub widgets and backend contract](payment-hub-widgets.md)
for setup, request metadata, and the distinction between definitions, local
instances, refreshed content, and future purchases.

## Connection removal and input isolation

Remove the current connection before changing wallets. Removal is blocked during
active payment preparation or submission. Each provider keeps one payment on
the loading screen until it completes; a provider-side pending response is
checked in that same foreground operation. Recent holds only the last ten resolved
payments for the current process. If the outcome cannot be confirmed, check the
original wallet before paying again; an uncertain outcome is not recorded as a
failure in Recent.
Opening a successful payment in Recent shows its receipt: amount and fee in the
selected currency, SAT equivalents for fiat, the available LNURL recipient and
domain, and a local timestamp. When the provider supplies a payment preimage,
the receipt includes its QR code, copy action, and explanation. Blink receipts
also explain that Blink may partially refund the fee. The immediate payment
result has no receipt action or refund note.
Removal does not cancel or reverse a payment.

Keep imported contacts, onboarding completion, general preferences, and local Hub
widgets, layout, and action history. Imported Blink contacts remain available across
connection changes. Erase credentials, connection-specific settings, payment
session records, and queued input. Close provider resources and invalidate
outstanding work before mounting another connection.

Interrupted cleanup resumes after restart. Incomplete cleanup presents a blocking
retry screen; a new connection/payment flow cannot start until cleanup finishes.
Even another connection to the same provider cannot restore the previous wallet's
payment attempts. Blink and NWC reuse their guards in Blip and Lasr; Spark retains
its own removal semantics.

Rayl 1.0 does not register NWC connection URLs and rejects directly delivered NWC
links. `RAYL_AVAILABLE_WALLETS` controls both native choice lists and selection
validation, including persisted selection. Enabling another provider requires a
mobile release, its URL registrations, and corresponding product declarations.
Provider implementations and separate connection stores remain in place.
Payment input routes only to the connected experience. An unconfigured user must
complete setup and reopen the request. Never replay queued input across connection
replacement.

## Native UI and storage

Shared Kotlin holds state and presentation snapshots. Android renders Compose
from `androidMain`; iOS renders SwiftUI/UIKit from `iosMain/swift`. Each consuming
Xcode target registers shared renderer directories once, with explicit resource
catalog references. See [the native app shell](native-shell.md).

App roots assemble dependencies and navigation. Provider experiences consume
shared presentation directly; provider decisions stay with their provider.
Do not add forwarding wrappers, a shared payment coordinator, or provider flags
to disguise different behavior.

Keep app-specific storage and separate named provider connection stores. Blink
and NWC keep only resolved receipts in memory; neither persists or restores
payment attempts. Spark keeps its session receipts in memory separately from its
SDK-owned wallet storage. Providers must never receive the same
connection-settings instance. On iOS, general preferences use standard
app-specific UserDefaults; erasable provider data uses the explicit named
connection-settings factory.

## Identity and resources

- Android application ID and iOS bundle ID: `com.nicolasusca.rayl`.
- Kotlin/Android namespace: `xyz.lilsus.rayl`; Gradle prefix: `:rayl`.
- Android variants add `.dev` and `.e2e`; iOS E2E adds `.e2e`.
- Maintain English, German, and Spanish resources together.
- iOS/iPadOS deployment target is at least 18.5 for normal and E2E targets.
