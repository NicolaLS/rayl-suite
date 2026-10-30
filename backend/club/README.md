# Rayl Club backend

Club accounts, invites, memberships and credit spends, served by the Rayl backend
(`backend/rayl`) under `/v1/club`.
The app-facing contract is `ClubApi` in [`core/rayl-api`](../../core/rayl-api).

## Configuration

The Club is served only when `DATABASE_URL` and `SUPABASE_URL` are set; otherwise the Rayl
backend runs without it.

| Variable | Value |
|---|---|
| `DATABASE_URL` | `postgresql://user:password@host:port/database` (Railway: `${{Postgres.DATABASE_URL}}`) |
| `SUPABASE_URL` | Supabase project URL; access tokens are verified against its JWKS |
| `CLUB_OPERATOR_TOKEN` | Optional. Enables the operator endpoints below |
| `CLUB_JOIN_IN_APP` | Platforms whose apps may offer joining: `ios,android` (default), one of them, or `none` |
| `CLUB_TEST_TIERS` | `true` lets invites carry the $1 `test` tier. Regtest only |
| `TREASURY_MNEMONIC` | The treasury wallet's seed; without it, paid joins and credit spends are unavailable |
| `CLUB_NETWORK` | `regtest` (default) or `mainnet` |
| `BREEZ_API_KEY` | Required on mainnet |
| `TREASURY_DIR` | The wallet's storage directory (default `/tmp/rayl-treasury`) |
| `CLUB_EXPOSURE_CAP_SAT` | What all members may owe and have in flight together. Credit spending is off while unset |
| `CLUB_PAUSED` | `true` is the kill switch: credit spends and joins are declined |

The database may be unreachable at startup: the schema is applied once it can be reached, and
Club requests answer 503 `club_unavailable` until then. The schema is
`src/main/resources/club/schema.sql`. The Club is pre-release, so a schema change means resetting
the database, not a migration.

## Kill switch

Set `CLUB_PAUSED=true` on the service (Railway redeploys it, about 1–2 minutes), and remove it
to resume. While paused, apps pay from the pocket and joins answer `club_unavailable`. Payments
already in flight still complete, and fees already paid still activate memberships. Lowering
`CLUB_EXPOSURE_CAP_SAT` the same way limits new spends without stopping them.

## Operator endpoints

`Authorization: Bearer $CLUB_OPERATOR_TOKEN`. Create an invite (`tierCodes` from `member`, `plus`;
`expiresInDays` defaults to 30, `null` for none):

```sh
curl -sS -X POST "$RAYL_BACKEND/v1/club/operator/invites" \
  -H "Authorization: Bearer $CLUB_OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"tierCodes":["member"],"feeWaived":true,"note":"App Review"}'
```

List invites with `GET` on the same path.

## Tests

`./gradlew :backend:club:test` runs against an embedded Postgres 18 (no Docker needed).
