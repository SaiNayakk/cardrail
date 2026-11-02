-- Phase 4: credit card statements, interest and payments.

alter table accounts add column apr_bps int not null default 2499;   -- 24.99% a year, a typical US card's purchase rate

-- One statement per cycle for each credit account: what happened, what is owed, the least that must be paid by when.
create table statements (
    id                 text primary key,             -- st_...
    account_id         uuid not null references accounts (id),
    period_start       timestamptz not null,
    period_end         timestamptz not null,
    opening_cents      bigint not null,
    purchases_cents    bigint not null,
    refunds_cents      bigint not null,
    payments_cents     bigint not null,
    interest_cents     bigint not null,
    fees_cents         bigint not null,
    closing_cents      bigint not null,              -- owed at the end of the period
    minimum_due_cents  bigint not null,
    due_at             timestamptz not null,
    created_at         timestamptz not null default now(),
    unique (account_id, period_end)
);

create index statements_account on statements (account_id, period_end desc);

-- Money the cardholder paid towards a credit card, from their account elsewhere.
create table card_payments (
    id          text primary key,                    -- cp_...
    account_id  uuid not null references accounts (id),
    amount_cents bigint not null check (amount_cents > 0),
    created_at  timestamptz not null default now()
);

create index card_payments_account on card_payments (account_id, created_at);
