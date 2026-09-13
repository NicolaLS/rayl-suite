# Payment Hub widgets and backend contract

The Payment Hub combines locally owned payment widgets with a catalogue of
backend-defined widgets rendered by native code. Android uses Compose and iOS
uses SwiftUI/UIKit. Descriptors supply values for supported controls and action
handlers; they do not download code, define arbitrary navigation, or invoke
native APIs.

## Implemented backend and client

- `core:hub-api` contains serializable Kotlin wire types for Android, iOS, and JVM.
- `integration:hub` contains the Ktor HTTP client and bounded response parsing.
- `backend:hub` is a runnable JVM Ktor server. Its catalogue is initially empty.
- `GET /hub/v1/widgets` returns `{"protocolVersion":1,"widgets":[]}`.
- `POST /hub/v1/widgets/{widgetId}/content` returns `404` with
  `{"code":"widget_not_found"}` because no remote widgets are published yet.

The client supports `metric/v1` definitions and typed metric content. A metric
variant selects the compiled `metric` template and exactly one size: `small`,
`wide`, or `large`. Different finished compositions have different variant IDs;
the descriptor does not offer a second independent resizing decision.

There are no live Bitcoin-price, SilentLink, Claro, Tigo, or other supplier
integrations behind this endpoint. Example fixtures describe the wire format
and are never loaded as catalogue entries by the server.

## Local development

Use JDK 21 and start the server from the repository root:

```sh
./gradlew :backend:hub:run
```

The default listener is `127.0.0.1:8080`. `HUB_HOST` and `HUB_PORT` configure the
listener. No public deployment is assumed or created.

```sh
curl http://127.0.0.1:8080/hub/v1/widgets \
  -H 'X-Rayl-App: com.nicolasusca.rayl' \
  -H 'X-Rayl-Version: 1.0.0' \
  -H 'X-Rayl-Build: 1' \
  -H 'X-Rayl-Platform: ios' \
  -H 'X-Rayl-Hub-Contracts: metric/v1' \
  -H 'Accept-Language: en'
```

Development on `main` targets local-only Rayl and Blip 1.0. Mobile builds use
the local Hub only. Their source configuration
fixes the backend URL to an empty value and ignores `rayl.hub.baseUrl` and
`RAYL_HUB_BASE_URL`. Neither native platform creates a remote session in this
configuration. Local widgets remain available without a Hub backend. The JVM
server and wire fixtures remain available for contributor development.

The HTTP client accepts HTTPS, plus HTTP loopback development hosts `localhost`,
`127.0.0.1`, `::1`, and Android emulator host alias `10.0.2.2`. Android and Apple
transport policies still apply; use an HTTPS development endpoint unless the
relevant Debug configuration explicitly permits local HTTP. Never weaken a
Release transport policy to test this endpoint.

## Request metadata

Every request includes the same metadata, including content refreshes:

| Header | Meaning |
| --- | --- |
| `X-Rayl-App` | Full native package/bundle identifier, such as `com.nicolasusca.rayl` |
| `X-Rayl-Version` | Native user-facing app version |
| `X-Rayl-Build` | Native build identifier |
| `X-Rayl-Platform` | `android` or `ios` |
| `X-Rayl-Hub-Contracts` | Comma-separated contracts actually supported by the client |
| `Accept-Language` | Requested locale; native local widgets retain their bundled localization |

The server rejects missing metadata with `400` and
`{"code":"client_metadata_required"}`. An empty supported-contract list is
allowed. Metadata is not authentication and contains no device identifier.
The client advertises the intersection of the supplied capabilities and its
implemented contracts: currently at most `metric/v1`. Unknown capabilities are
never advertised, and content requests are not sent when metric support is disabled.

## Four separate data boundaries

### Catalogue definition

The catalogue describes a widget that may be added. A definition has a stable
ID, an opaque revision, a contract, localized title/description, finished native
variants, configuration fields, and bounded actions. It does not contain a
user's saved configuration or position.

The current metric contract accepts text, phone, and single-choice fields, and
only the `refresh` action kind. Refresh uses the built-in native controls; the
current native presentation does not render descriptor action IDs or titles as
custom buttons. Omitting `actions` does not disable native refresh.

A choice stores its stable option ID, not its label. Every option ID must fit the
field's maximum length, otherwise the definition is skipped. The maximum is 256
characters when unspecified. Native editing checks required values, choice
membership, and maximum input lengths. `phone` selects a phone keyboard; it does
not imply an implemented phone-number normalization or validation standard.

The HTTP client validates transport bounds (at most 16 configuration keys, valid
key identifiers, and at most 256 characters per value). It does not retain a
catalogue or revalidate a saved configuration against the newest field schema.
A future content handler must validate fields and values on every request before
looking up data. The current empty backend returns 404 without processing them.

See [the metric catalogue fixture](api/payment-hub/catalog-metric.example.json)
and [its schema](api/payment-hub/metric-catalog.schema.json). Identifiers use
letters, digits, dots, underscores, and hyphens, with a maximum length of 128.
The `local.` definition prefix is reserved for the app.

### Local widget instance

An instance has its own stable local identity, a definition ID, and selected
variant. The app-owned `HubRecord` stores contacts, reusable payment actions,
and an ordered `widgets` array in one atomic document. Array order determines
canvas arrangement; there is no separate remote layout document.

A stored widget contains `id`, `definitionId`, `kind`, `variantId`, `columns`,
`rows`, `capacity`, and optional `title`. Contacts widgets keep ordered
`contactIds`; a Shortcut references one saved payment recipe through `actionId`;
remote metric instances keep their field values in `configuration`. The shape
is illustrated in [the local instance fixture](api/payment-hub/local-instance.example.json).
This is local persistence, not a server API or synchronization format.

| Local definition | Variants | Contents |
| --- | --- | --- |
| `local.contacts` | Single, Row, Card | Up to 1, 4, or 6 selected contacts, each asking for an amount |
| `local.shortcut` | Single | One contact with a configured amount and optional comment |
| `local.favorites` | Row, Card | Successful payment actions ranked by use count |
| `local.recents` | Row, Card | Successful payment actions ranked by most recent use |

Local definitions share the gallery with remote definitions. Their contacts,
configuration, payment history, and arrangement do not need HTTP. Multiple
widgets can reference the same contact payment action, so placing or removing a
widget does not duplicate or erase that action's history. Shortcut recipes with
the same contact, amount, and comment reuse their payment action.

Both native platforms show one widget grid with an **Add widget** tile at the end,
including when no widgets exist. Creation starts with an adaptive grid of illustrated
widget types, with titles and descriptions below the illustrations. Layout previews
use saved contacts when available and localized placeholders for remaining slots;
Shortcut previews also show the preset-amount label. Creation chooses the widget and
layout once in the gallery, then opens its configuration. Contact selection has its
own searchable picker: Single and Shortcut replace the selected contact, while Row
and Card show their capacity and allow multiple selections. Search results remain
scrollable above the keyboard. Custom widget titles do not replace the visible recipient name.
Widgets can be reordered by dragging or through their native action menu; Android
exposes that menu with a More button, and iOS uses a context menu.

Removing a widget needs no confirmation and preserves contacts, payment actions,
and history. Deleting a contact asks for confirmation, removes its payment
actions and Shortcuts, and prunes Contacts memberships; an empty Contacts widget
is removed. The post-payment save prompt creates a contact and a single Contacts
widget together. Automatic import after a new Blink connection populates only
the contact book.

### Refreshed content

A metric instance sends its selected variant and configuration in a JSON POST
body, keeping configuration values out of query strings:

```json
{"variantId":"usd-wide","configuration":{}}
```

The response identifies the requested widget/variant and contains a metric with
an exact decimal string, unit, label, ISO-8601 `asOf` timestamp, and optional
`refreshAfterSeconds`. Values never use JSON floating-point numbers: `"65000.00"`
with unit `"USD"` is a display value of 65,000 US dollars, not 65,000 cents. The
client preserves the decimal text without conversion or rounding. A metric is
informational content, not an authorized payment amount or exchange-rate quote.

A suggested refresh interval must be between 30 seconds and one day; it is not
permission for unrestricted background work. The current surface refreshes all
its metric instances together, defaults to five minutes, and uses the shortest
suggested interval with a five-minute upper bound. The wire value is a scheduling
hint rather than a guarantee that an individual widget is polled at that interval.

See [the metric content fixture](api/payment-hub/content-metric.example.json)
and [its schema](api/payment-hub/metric-content.schema.json). Displaying cached
content must preserve its age; a failed refresh must not make old data appear
fresh. Credentials for a future authorized balance lookup must not be placed in
ordinary widget configuration or logged by request handlers.

### Future service purchase

Service purchasing is not implemented. The [service catalogue fixture](api/payment-hub/catalog-service.future.example.json)
and [future schema](api/payment-hub/service-catalog.future.schema.json) are
design examples. The current client skips `service/v1` and purchase actions.

A future implementation must distinguish requested service denomination from
the quoted payment price. It prepares an expiring order, returns an exact total
and Lightning invoice, delegates payment to the selected wallet provider, and
retrieves fulfillment status from the backend. Wallet settlement and supplier
delivery are separate facts. Supplier adapters own fulfillment and recovery;
widget descriptors do not own payment retries or wallet policy.

The [future purchase exchange](api/payment-hub/purchase.future.example.json)
illustrates exact minor-unit amounts, revision validation, idempotency, and
anonymous order recovery. In that fixture, `"minor":"500","currency":"USD"`
means $5.00, and the separate `amountMsat` is an integer count of millisatoshis.
These integer strings must not be parsed through floating point. A future
implementation must verify the invoice amount against the prepared quote and
its expiry; the fixture does not implement currency conversion or assert a live
exchange rate. These are not implemented endpoints. Anonymous order
tokens are credentials; they need secure local storage. An app version or device
identifier cannot authorize access to an order.

## Versions and unavailable content

`protocolVersion` versions the wire envelope. `metric/v1` versions a supported
native behavior contract. The definition's `revision` identifies catalogue
changes. A service identity or future `claro-topup/v1` contract identifier is
opaque to the client; it is not a route or a Kotlin class name. No catalogue
caching revision or ETag is required by this initial server.

The client parses definitions independently. Unsupported contracts/templates,
invalid entries, duplicate IDs, and reserved local IDs are skipped without
discarding other valid entries. The available result reports the skipped count.
Malformed envelopes and unsupported protocol versions return unavailable states.
Responses are bounded to 256 KiB and 128 catalogue entries, with bounded fields,
variants, choices, and text lengths. Requests have a 15-second deadline and
coroutine cancellation remains cancellation. The caller owns the HTTP client;
transport retry policy may retry read-only content requests within that deadline.
HTTP error status is classified by this client even if the caller enabled Ktor's
`expectSuccess` option. Local definition IDs never produce content HTTP requests.

Absent configuration, transport failure, malformed responses, and content `404`
are distinct client results. An empty successful catalogue is a normal result.
Temporary remote unavailability must not delete a saved local widget instance.
Backend services can be added without a mobile release only when their data and
actions fit contracts already implemented and available for native app review.

## Focused verification

```sh
./gradlew :integration:hub:jvmTest :backend:hub:test
```

These focused checks exercise metadata, per-item compatibility, malformed/offline
results, exact content identity/values, the empty endpoint, and unknown content.
They do not validate supplier integrations or replace native device QA.
