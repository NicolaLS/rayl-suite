-- Rayl Club schema, applied idempotently at startup. The Club is pre-release: schema changes
-- replace this file and the database is reset; there are no migrations.

create table if not exists club_account (
    id uuid primary key,
    -- The Supabase Auth user id (`sub`). No email or other personal data is stored.
    auth_subject uuid not null unique,
    created_at timestamptz not null default now()
);

create table if not exists club_invite (
    -- Normalized: 12 characters from InviteCodes.ALPHABET, without dashes.
    code text primary key,
    tier_codes text[] not null,
    fee_waived boolean not null,
    -- Operator note, e.g. who the invite is for.
    note text,
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    -- `redeemed_at` marks the invite used for good; `redeemed_by` clears if the account is deleted.
    redeemed_at timestamptz,
    redeemed_by uuid unique references club_account (id) on delete set null
);
