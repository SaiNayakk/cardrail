-- Meridian: one company that issues the cards, signs up the merchants and runs the network between them.

-- Cardmembers' accounts. A Meridian card is a charge card: no preset limit in principle, here a spending limit, and
-- the balance owed is due in full each month.
create table accounts (
    id                 uuid primary key,
    name               text not null,
    balance_cents      bigint not null default 0,    -- owed
    limit_cents        bigint not null,
    created_at         timestamptz not null default now()
);

create table cards (
    id            uuid primary key,
    account_id    uuid not null references accounts (id),
    pan_hash      bytea not null unique,
    pan_encrypted bytea not null,
    last4         char(4) not null,
    expiry        char(4) not null,               -- YYMM
    status        text not null check (status in ('ACTIVE', 'BLOCKED', 'LOST')),
    label         text,
    created_at    timestamptz not null default now()
);

-- Merchants Meridian pays directly, at its own discount rate. Known by the acquirer's merchant id: a processor
-- (Railpay Acquiring) passes Meridian cards straight to Meridian.
create table merchants (
    id            uuid primary key,
    acquirer_id   text not null,
    mid           text not null,
    name          text not null,
    discount_bps  int not null default 300,       -- 3.00%: Meridian keeps it all, there is no interchange to share
    created_at    timestamptz not null default now(),
    unique (acquirer_id, mid)
);

create table authorisations (
    id             uuid primary key,
    card_id        uuid references cards (id),
    account_id     uuid references accounts (id),
    merchant_id    uuid references merchants (id),
    acquirer_id    text not null,
    rrn            text not null,
    kind           text not null default 'PURCHASE' check (kind in ('PURCHASE', 'REFUND')),
    amount_cents   bigint not null,
    response_code  char(2) not null,
    auth_code      char(6),
    status         text not null check (status in ('APPROVED', 'DECLINED', 'REVERSED', 'SUBMITTED')),
    hold_cents     bigint not null default 0,
    submitted_cents bigint not null default 0,
    created_at     timestamptz not null default now(),
    unique (acquirer_id, rrn)
);

create index meridian_open_holds on authorisations (account_id) where status = 'APPROVED';

-- What Meridian paid each merchant: its submitted sales less refunds and the discount.
create table payouts (
    id            text primary key,
    merchant_id   uuid not null references merchants (id),
    amount_cents  bigint not null,
    sales_cents   bigint not null,
    refunds_cents bigint not null,
    discount_cents bigint not null,
    created_at    timestamptz not null default now()
);

alter table authorisations add column payout_id text;

create table journals (
    id          uuid primary key,
    key         text not null unique,
    description text not null,
    created_at  timestamptz not null default now()
);

create table postings (
    id          bigserial primary key,
    journal_id  uuid not null references journals (id),
    account     text not null,
    cents       bigint not null check (cents <> 0)
);

create index postings_account on postings (account);
