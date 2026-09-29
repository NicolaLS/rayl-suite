# Rayl backend

The Rayl app's backend ([wire contract](../../core/rayl-api)):

- `GET /v1/bitcoin/prices`: every 30 seconds it polls mempool.space, Coinbase, Kraken and
  blockchain.info and answers with the per-currency median of quotes at most five minutes old.
  It returns 503 when no source is fresh.
- `GET /v1/catalog`: the discoverable reviewed services for the environment, from
  [`catalog/<env>.json`](src/main/resources/catalog). Every minute it fetches external
  definitions like a client would and validates them with the app's parser. First-party
  definitions come directly from the running backend and pass the same validation; entries without a
  valid definition are listed without one, and the app hides them. Updated clients request
  `?includeLinkOnly=true` to retain review metadata for services distributed by link. Both
  responses include the complete block list.
- `GET /v1/widgets/{id}.json` and `POST /v1/widgets/{id}/status`: ordinary provider-protocol
  definitions and status for `bitcoin-price` and `block-time`. Price uses the aggregated USD
  quote; block time uses mempool.space block headers polled every 30 seconds. Both return 503
  when no observation is fresh within five minutes. Block age is measured at the observation
  time, which is also the response's `asOf`; it is not the block header timestamp.
  The block tile shows height and elapsed time; the price tile supports small and medium
  sizes with the lab's dark tint, icon, BTC label and sparkline. Definitions include clearly
  separate sample snapshots for previews; unavailable live data never uses the sample.
  Optional mempool.space USD history is polled every 30 minutes (retrying failures after five
  minutes), streamed with a 2 MiB limit and trimmed
  to the last 24 hourly points within one day. The current aggregated price ends the trend.
  Failed history polls leave the price working; cached history is omitted after two hours.
- `GET /v1/status`: observation freshness, last success and last error for block and history
  polls, alongside existing rate, catalog and usage health. All upstream requests have a
  ten-second timeout; status requests read caches and never start an upstream call.
- `POST /v1/usage`: anonymous daily counts of added, removed and paid, per catalog entry and
  platform, in the `usage_daily` table. Nothing identifies a user or a device.

## Environments

Railway project `rayl-backend`, service `rayl`, each environment with its own Postgres:

| Environment | URL | Used by |
| --- | --- | --- |
| `staging` | https://rayl-staging.up.railway.app | explicit staging API clients |
| `production` | https://rayl-production.up.railway.app | default app price endpoint |

Variables: `RAYL_ENV` (`staging` or `production`, required), `DATABASE_URL`
(`${{Postgres.DATABASE_URL}}`), `PORT=8080`, and optionally `RAYL_BLOCKED`.

## Changing what Rayl recommends

- **Add, change or delist** a service: edit `catalog/<env>.json` and deploy. Delisting only
  hides it from the Add widget sheet; widgets users already added keep working, without the
  reviewed brand. `CatalogTest` and startup both reject an invalid catalog.
- **Block** a service (malicious, legal): add its host (`evil.example`, which also covers
  subdomains) or its exact definition URL to `blocked` and deploy. In an emergency, set the
  Railway variable `RAYL_BLOCKED` instead (comma-separated). Railway restarts the service in
  about a minute, with no build. Apps refresh the catalog on the Hub and before every widget
  payment once it is five minutes old, and blocked widgets can neither be added nor paid.
  Move emergency rules into the JSON afterwards, so they survive the next deploy's review.

## Running and deploying

```sh
./gradlew :backend:rayl:run          # staging catalog on 127.0.0.1:8080, usage to the log
```

The image packages the locally built distribution, so Railway never configures the Gradle
project:

```sh
./gradlew :backend:rayl:installDist
railway up backend/rayl --path-as-root --no-gitignore \
  -p 9bbf2625-4edc-4a92-bc7c-b3e9f6c840be -s rayl -e staging --detach   # or -e production
```

Usage per service, in Railway's Postgres data view:

```sql
SELECT entry_id, event, sum(count) FROM usage_daily
WHERE day > current_date - 30 GROUP BY entry_id, event ORDER BY entry_id, event;
```

Catalog entries may set `"discoverable": false` to retain review identity, definition validation,
usage accounting and block policy for a service distributed by link without showing it in the
Add widget sheet. The entry is still public metadata; this is not a private access mechanism.
Claro and development fixtures on staging use this while beta testers exercise real purchases
and failure cases. Both environment source catalogs advertise only Bitcoin price and Block time;
production has no purchase entries. Source catalog changes take effect in an environment only
when that backend is deployed.

## First-party widget links

| Widget | Staging | Production |
| --- | --- | --- |
| Bitcoin price | [definition](https://rayl-staging.up.railway.app/v1/widgets/bitcoin-price.json) | [definition](https://rayl-production.up.railway.app/v1/widgets/bitcoin-price.json) |
| Block time | [definition](https://rayl-staging.up.railway.app/v1/widgets/block-time.json) | [definition](https://rayl-production.up.railway.app/v1/widgets/block-time.json) |

Styling and the embedded PNG were carried over from the Claro widget lab. The provider is
Rayl; live prices come from Rayl's aggregated quote service. Historical sparkline points and
Bitcoin block headers come from mempool.space. History and block observations are process
caches; a restart refills them, and live status returns 503 until its required source is ready.
