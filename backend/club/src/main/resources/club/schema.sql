-- Rayl Club schema, applied idempotently at startup. The Club is pre-release: schema changes
-- replace this file and the database is reset; there are no migrations.

create table if not exists club_account (
    id uuid primary key,
    -- The Supabase Auth user id (`sub`). No email or other personal data is stored.
    auth_subject uuid not null unique,
    -- Shown to the member (e.g. for joining on the web); 10 characters from InviteCodes.ALPHABET.
    member_code text not null unique,
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

-- A membership keeps a snapshot of its tier's terms, so changing the catalog never changes it.
create table if not exists club_membership (
    id uuid primary key,
    account_id uuid not null references club_account (id),
    tier_code text not null,
    fee_usd_cents bigint not null,
    line_usd_cents bigint not null,
    status text not null check (status in ('pending_payment', 'active', 'abandoned')),
    term_start timestamptz,
    term_end timestamptz,
    -- Fixed for the term: the tier's line converted at the fee quote's rate (D-04).
    credit_limit_msat bigint,
    created_at timestamptz not null default now()
);
create unique index if not exists club_membership_one_active
    on club_membership (account_id) where status = 'active';

-- The annual fee as a treasury invoice, priced in sats at quote time.
create table if not exists club_fee_quote (
    id uuid primary key,
    membership_id uuid not null references club_membership (id),
    amount_msat bigint not null,
    btc_usd numeric not null,
    invoice text not null unique,
    expires_at timestamptz not null,
    paid_at timestamptz,
    created_at timestamptz not null default now()
);

-- Append-only double-entry ledger in msat; each entry's postings sum to zero. (kind, ref_id)
-- makes recording an event idempotent.
create table if not exists club_journal_entry (
    id uuid primary key,
    kind text not null,
    ref_id uuid not null,
    created_at timestamptz not null default now(),
    unique (kind, ref_id)
);

create table if not exists club_posting (
    entry_id uuid not null references club_journal_entry (id),
    ledger_account text not null,
    member_account_id uuid references club_account (id),
    amount_msat bigint not null
);

-- A payment the treasury made for a member on credit. While `paying`, its amount plus the quoted
-- fee is reserved against the member's line; once `settled`, the ledger holds what is owed.
create table if not exists club_credit_spend (
    id uuid primary key,
    account_id uuid not null references club_account (id),
    membership_id uuid not null references club_membership (id),
    -- Chosen by the app; a repeated request answers the original spend.
    idempotency_key text not null,
    invoice text not null,
    amount_msat bigint not null check (amount_msat > 0),
    reserved_fee_msat bigint not null check (reserved_fee_msat >= 0),
    fee_msat bigint,
    status text not null check (status in ('paying', 'settled', 'failed')),
    -- The treasury wallet's payment id, once known; the reconciler looks the payment up by it.
    payment_id text,
    preimage text,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    unique (account_id, idempotency_key)
);
-- An invoice is paid with credit at most once.
create unique index if not exists club_credit_spend_one_payment
    on club_credit_spend (invoice) where status <> 'failed';

-- A closed monthly statement (P-03, P-05). `closing_msat` is the member receivable at
-- `period_end`, so it includes any earlier unpaid statement. It is paid once repayments recorded
-- after `period_end` add up to it.
create table if not exists club_statement (
    id uuid primary key,
    account_id uuid not null references club_account (id),
    membership_id uuid not null references club_membership (id),
    period_start timestamptz not null,
    period_end timestamptz not null,
    closing_msat bigint not null,
    due_at timestamptz not null,
    paid_at timestamptz,
    created_at timestamptz not null default now(),
    unique (membership_id, period_end)
);

-- A treasury invoice a member pays to repay their balance. At most one is open per account.
create table if not exists club_repayment (
    id uuid primary key,
    account_id uuid not null references club_account (id),
    invoice text not null unique,
    amount_msat bigint not null check (amount_msat > 0),
    expires_at timestamptz not null,
    paid_at timestamptz,
    created_at timestamptz not null default now()
);
