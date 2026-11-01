-- Railpay, the gateway. Money in US cents.

create table merchants (
    id                  text primary key,          -- mer_...
    name                text not null,
    acquirer_mid        char(15) not null unique,  -- the merchant's id at Railpay Acquiring (field 42)
    secret_key_hash     bytea not null unique,     -- SHA-256 of the secret key; the key itself is shown once
    secret_key_hint     text not null,             -- sk_test_ and the last 4, to recognise a key by
    publishable_key     text not null unique,
    webhook_url         text,
    created_at          timestamptz not null default now()
);

create table payments (
    id              text primary key,              -- pay_...
    merchant_id     text not null references merchants (id),
    amount_cents    bigint not null check (amount_cents > 0),
    currency        char(3) not null,
    description     text,
    status          text not null check (status in ('PENDING', 'AUTHORISED', 'DECLINED', 'UNKNOWN', 'FAILED')),
    card_brand      text not null,
    card_bin        char(6) not null,
    card_last4      char(4) not null,
    rrn             char(12) not null unique,      -- the reference every party knows the payment by
    auth_code       char(6),
    response_code   char(2),
    decline_reason  text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index payments_merchant on payments (merchant_id, created_at desc);

-- A write sent twice with the same Idempotency-Key gets the first answer, without being done twice.
create table idempotency_keys (
    merchant_id     text not null,
    key             text not null,
    request_hash    bytea not null,
    status_code     int,                           -- null while the first request is still being worked on
    body            text,
    created_at      timestamptz not null default now(),
    primary key (merchant_id, key)
);

create sequence rrn_seq;
