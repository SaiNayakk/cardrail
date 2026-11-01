-- Phase 2: captures, voids, refunds, resolving unknown outcomes, and webhooks.

alter table payments
    add column capture         boolean not null default true,   -- capture as soon as it is authorised
    add column captured_cents  bigint not null default 0,
    add column refunded_cents  bigint not null default 0,
    add column card_ref        text;                             -- the vault's kept card, for refunds

alter table payments drop constraint payments_status_check;
alter table payments add constraint payments_status_check
    check (status in ('PENDING', 'AUTHORISED', 'DECLINED', 'UNKNOWN', 'CAPTURED', 'VOIDED', 'REVERSED', 'FAILED'));

create index payments_unknown on payments (updated_at) where status = 'UNKNOWN';

create table refunds (
    id              text primary key,                -- re_...
    payment_id      text not null references payments (id),
    amount_cents    bigint not null check (amount_cents > 0),
    status          text not null check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    rrn             char(12) not null unique,
    response_code   char(2),
    failure_reason  text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index refunds_payment on refunds (payment_id, created_at);

-- Each merchant's webhook signing secret. The gateway must sign with it, so it is kept as it is.
alter table merchants add column webhook_secret text;

-- Webhooks are an outbox: an event is written in the same transaction as the change it reports, then delivered
-- (signed) until the merchant's server answers 2xx, with growing gaps, for up to three days.
create table webhook_events (
    id              text primary key,                -- evt_...
    merchant_id     text not null references merchants (id),
    type            text not null,
    payload         text not null,
    created_at      timestamptz not null default now(),
    attempts        int not null default 0,
    next_attempt_at timestamptz not null default now(),
    delivered_at    timestamptz,
    last_status     int,
    last_error      text
);

create index webhook_events_due on webhook_events (next_attempt_at) where delivered_at is null;
