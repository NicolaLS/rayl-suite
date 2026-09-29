# Backend runtime and trust boundaries

Widget definitions and status are public, read-only endpoints. Status accepts at most 8 KiB
of JSON and validates the protocol version, widget identity and string-valued input object.
Requests read cached observations and do not trigger upstream work. Catalog definitions from
Rayl itself pass the same widget parser as external definitions, without a self-network call.

The process polls fixed HTTPS upstreams with a ten-second request timeout and no redirects.
Block responses are bounded to 64 KiB; optional price history is bounded to 2 MiB, with only
24 recent points retained. A successful quote or block observation expires after five minutes;
failures retain its original timestamp. Unavailable live values return 503, never preview data.
History failure does not affect current prices; history is omitted after two hours.

Upstream data is informational. Block timestamps can be ahead of the local clock, and the
subtitle states this rather than inventing a negative age. Prices reuse the existing source
median; the historical sparkline uses mempool.space hourly prices and ends at Rayl's current
aggregated quote. These sources need not agree exactly.

`/v1/status` exposes observation freshness and polling errors without credentials or device
data. Changes to polling, response limits, expiry, or source ownership should preserve these
bounds and the cache-failure regression tests. Process caches refill on restart; a widget may
briefly return 503 until its first observation arrives. Public endpoints have no per-user
authentication or application rate limiter; status cost is bounded by cached, small responses.
