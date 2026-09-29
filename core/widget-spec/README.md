# Widget protocol implementation

The canonical protocol documentation lives in the private [Rayl Widget Specification
repository](https://github.com/NicolaLS/rayl-widget-spec), separately from the Rayl application:

- [Client specification](https://github.com/NicolaLS/rayl-widget-spec/blob/main/spec/clients.md)
- [Service specification](https://github.com/NicolaLS/rayl-widget-spec/blob/main/spec/services.md)

This module owns the parser and wire models shared by the app and catalog backend.
The contract remains an MVP draft until real checkout, recovery and independent interoperability
validation are complete. Protocol edits belong in the specification repository; implementation
changes and regression tests belong here.

[Deferred bills and gift-card design](https://github.com/NicolaLS/rayl-widget-spec/blob/main/design/future-services.md) is kept separately
from the active contract. The broader Rayl implementation remains preserved on
`archive/hub-prototype-bills-giftcards-2026-09-29` in this checkout.
