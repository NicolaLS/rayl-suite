# LNURL-pay behavior

Rayl's Blink experience, Blip, and Lasr use the shared LNURL-pay client and
invoice resolver. Flint's Spark SDK has its own implementation; this document
does not describe its transport or validation.

LNURL-pay and Lightning addresses require public HTTPS hostnames. IP literals,
local hostnames, onion services, and DNS answers containing private, loopback,
link-local, multicast, or reserved addresses are rejected. Every initial
endpoint and callback is checked. HTTP redirects are rejected, including HTTPS
redirects; a service must provide its final endpoint directly. Callbacks can
use a different public HTTPS domain from the initial service.

DNS checks use the platform resolver before each request. The native HTTP
engine performs its own connection lookup, so these checks do not pin the DNS
answer to the connection and cannot completely prevent DNS rebinding. Normal
TLS certificate and hostname validation remains enabled. This is a mobile
client destination restriction, not a general-purpose SSRF-resistant proxy.
Well-known NAT64 addresses are accepted only when their embedded IPv4 address
is public.

Responses are streamed with a 256 KiB limit before UTF-8 decoding and JSON
parsing, with JSON nesting limited to 32 levels. The client requires the exact
`payRequest` tag, a public HTTPS callback,
positive integer sendable amounts in a valid range, and one usable `text/plain`
metadata entry. Unknown metadata types may carry non-string values as allowed
by [LUD-06](https://github.com/lnurl/luds/blob/luds/06.md). Known metadata fields
must have the expected types. Remote error text and transport exceptions are
replaced with classified messages that do not include URLs or response bodies.

Amounts round upward to whole satoshis. Unrepresentable amounts, values outside
the advertised range, and rounding that would exceed that range are rejected
before requesting an invoice. The callback receives exactly one approved
`amount` and optional `comment`. Returned invoices must be unexpired, contain
the requested amount, and commit to the metadata hash or contain the exact
plaintext description; absent plaintext metadata never permits an arbitrary
invoice description.

The payment's domain and sanitized description are shown on amount entry and
confirmation screens. LNURL codes and Lightning addresses use the same payment
confirmation and Auto Pay policy as invoices; there is no separate LNURL review
setting. Once an amount is known, the app requests and validates the invoice
before applying the normal payment confirmation policy. A scanned fixed-amount
LNURL payment eligible for Auto Pay proceeds without a review screen. Manual-entry
policy, preset-target confirmations, and deep-link confirmations remain
provider-owned. Destination and invoice validation always apply.

Display and manual-entry currencies are Satoshi and the supported fiat currencies.
Satoshi is the only Bitcoin denomination; amounts are never displayed or entered
as fractional BTC. Fiat amounts use the current Bitcoin exchange rate for conversion.
With a fiat preference, each manual entry starts in that currency and offers a
switch to SAT plus an estimated equivalent. Switching changes only this entry,
not the saved preference. A Satoshi preference shows no switch or equivalent.
The selected entry currency determines the amount submitted after review.
Switching converts to the other currency's precision (upward to whole SAT);
switching back without edits restores the exact original input to avoid rounding
drift. Exchange-rate refreshes preserve the selected unit and input. Without a
rate, the equivalent is unavailable and a newly selected currency starts empty;
switching back can still restore the prior input. Background rate failures do
not dismiss the sheet, and SAT entry works without a rate.

Manual entry shows no minimum/maximum labels or preset buttons. Zero or empty
input keeps Continue disabled without an error. A positive amount outside the
allowed payment range disables Continue and shows a small inline “Amount is
too low” or “Amount is too large” error. Validation uses the whole-satoshi payment
amount, including conversion and upward rounding for fiat input. The error clears
when the entry returns to the permitted range.

Manual amount entry has two steps within the same sheet: enter an amount and tap
Continue, then review the entered amount, currency, and available recipient
details before tapping Pay. The sheet keeps its height across both steps, with
Continue and Pay anchored at the bottom and scrollable content above. Edit amount
returns to the keypad with the input preserved. Continue does not submit a payment;
Pay invokes provider validation, amount conversion, and payment submission.
No separate confirmation sheet opens,
regardless of Always or the Auto Pay threshold, including after an amountless
deep link. Amounts supplied by deep links still require confirmation; scanned
invoice amounts follow the Always or Auto Pay threshold setting.
