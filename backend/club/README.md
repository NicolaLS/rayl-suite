# Rayl Club backend

Club accounts and invites, served by the Rayl backend (`backend/rayl`) under `/v1/club`.
The app-facing contract is `ClubApi` in [`core/rayl-api`](../../core/rayl-api).

## Configuration

The Club is served only when both of these are set; otherwise the Rayl backend runs without it.

| Variable | Value |
|---|---|
| `DATABASE_URL` | `postgresql://user:password@host:port/database` (Railway: `${{Postgres.DATABASE_URL}}`) |
| `SUPABASE_URL` | Supabase project URL; access tokens are verified against its JWKS |
| `CLUB_OPERATOR_TOKEN` | Optional. Enables the operator endpoints below |

The database may be unreachable at startup: the schema is applied once it can be reached, and
Club requests answer 503 `club_unavailable` until then. The schema is
`src/main/resources/club/schema.sql`. The Club is pre-release, so a schema change means resetting
the database, not a migration.

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
