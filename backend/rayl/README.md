# Rayl backend

The Rayl app's backend ([wire contract](../../core/rayl-api)):

- `GET /v1/bitcoin/prices`: every 30 seconds it polls mempool.space, Coinbase, Kraken and
  blockchain.info and answers with the per-currency median of quotes at most five minutes old.
  It returns 503 when no source is fresh.
- `GET /v1/catalog`: the reviewed services for the environment, from
  [`catalog/<env>.json`](src/main/resources/catalog). Every minute it fetches each
  definition like a client would and validates it with the app's parser; entries without a
  valid definition are listed without one, and the app hides them.
- `POST /v1/usage`: anonymous daily counts of added, removed and paid, per catalog entry and
  platform, in the `usage_daily` table. Nothing identifies a user or a device.

## Environments

Railway project `rayl-backend`, service `rayl`, each environment with its own Postgres:

| Environment | URL | Used by |
| --- | --- | --- |
| `staging` | https://rayl-staging.up.railway.app | debug, E2E and benchmark builds |
| `production` | https://rayl-production.up.railway.app | release builds |

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
