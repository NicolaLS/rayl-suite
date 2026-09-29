# Rayl dev provider

A rayl-widget/1 provider with repeatable widgets and failure cases, for testing Rayl before and
alongside real adapters. It runs on Railway staging at https://dev-provider-staging.up.railway.app
(`GET /` lists every definition URL), and the staging catalog lists its widgets.

- **Widgets:** `packages` (product, with validation), `topup` (amount), `giftcard` (product,
  delivered as a voucher), `bill` (lookup with a status), `price` (status only).
- **Scenarios:** the first path segment, for example `/mismatch/packages.json`. `ok`, `down`,
  `slow`, `mismatch` (invoice 4x), `higher` and `lower` (5%), `refuse`, `soldout`, `nothingdue`,
  `undelivered`. Non-`ok` titles name their scenario.
- **Invoices** are valid mainnet BOLT11 from a throwaway key: they pass Rayl's checks, but no
  wallet can pay them. Prices use the staging backend's rate, like the app.

Local run (Rayl needs a public HTTPS host, so put a tunnel in front):

```sh
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
PORT=8797 PUBLIC_BASE=https://<tunnel> .venv/bin/python provider.py
```

Deploys with the backend workflow on pushes to `hub-revamp`, or by hand:
`railway up backend/dev-provider --path-as-root -s dev-provider -e staging`.
