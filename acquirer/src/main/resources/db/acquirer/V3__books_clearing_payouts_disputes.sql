-- Phase 3: Railpay Acquiring's books, presenting to Railnet, settlement, merchant payouts and disputes.

-- Double-entry books, debits positive. Accounts:
--   merchant:<mid>     negative: what we owe the merchant; positive: what it owes us (after chargebacks)
--   due:railnet        positive: what Railnet owes us
--   bank:operating     our money
--   income:mdr, expense:interchange, expense:scheme-fees
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

alter table authorisations
    add column presented_cents   bigint,               -- what was presented: the capture, or the refund
    add column mdr_cents         bigint,               -- what the merchant pays us for it
    add column presented_file    text,
    add column presentment_error text,                 -- why Railnet refused it, if it did
    add column settlement_id     text,
    add column payout_id         text;

create index authorisations_to_present on authorisations (id)
    where presented_file is null and status in ('CAPTURED', 'APPROVED');

create table cursors (
    name   text primary key,
    value  text not null
);

-- A chargeback from the cardholder's bank. The merchant answers with evidence (sent as a representment) or accepts it.
create table disputes (
    id                  text primary key,          -- the issuer's dispute id
    rrn                 char(12) not null references authorisations (rrn),
    merchant_id         uuid not null references merchants (id),
    amount_cents        bigint not null,
    reason_code         char(4) not null,
    status              text not null check (status in ('NEEDS_RESPONSE', 'REPRESENTING', 'WON', 'LOST')),
    evidence            text,
    chargeback_settlement_id     text,
    representment_file           text,
    representment_settlement_id  text,
    chargeback_payout_id         text,
    representment_payout_id      text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

-- What a merchant was paid, and when: everything settled since its last payout, less fees and chargebacks.
create table payouts (
    id            text primary key,                 -- po_...
    merchant_id   uuid not null references merchants (id),
    amount_cents  bigint not null check (amount_cents > 0),
    sales_cents   bigint not null,
    refunds_cents bigint not null,
    fees_cents    bigint not null,
    disputes_cents bigint not null,
    created_at    timestamptz not null default now()
);

-- What the gateway must be told (a dispute, a payout), delivered until it answers.
create table notifications (
    id            bigserial primary key,
    payload       text not null,
    created_at    timestamptz not null default now(),
    attempts      int not null default 0,
    next_attempt_at timestamptz not null default now(),
    delivered_at  timestamptz
);

create index notifications_due on notifications (next_attempt_at) where delivered_at is null;
