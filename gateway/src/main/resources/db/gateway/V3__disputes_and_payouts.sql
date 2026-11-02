-- Phase 3: what the acquirer tells Railpay about chargebacks and payouts, for merchants to see and answer.

create table disputes (
    id            text primary key,                  -- the issuer's dispute id, as the acquirer passes it on
    payment_id    text not null references payments (id),
    merchant_id   text not null references merchants (id),
    amount_cents  bigint not null,
    reason_code   char(4) not null,
    status        text not null check (status in ('NEEDS_RESPONSE', 'UNDER_REVIEW', 'WON', 'LOST')),
    evidence      text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index disputes_merchant on disputes (merchant_id, created_at desc);

create table payouts (
    id             text primary key,                 -- po_..., the acquirer's
    merchant_id    text not null references merchants (id),
    amount_cents   bigint not null,
    sales_cents    bigint not null,
    refunds_cents  bigint not null,
    fees_cents     bigint not null,
    disputes_cents bigint not null,
    created_at     timestamptz not null default now()
);

create index payouts_merchant on payouts (merchant_id, created_at desc);
