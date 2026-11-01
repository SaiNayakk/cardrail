-- Kestrel Bank's books. All money in US cents.

create table cardholders (
    id          uuid primary key,
    name        text not null,
    created_at  timestamptz not null default now()
);

-- A debit account holds the customer's money (balance_cents). A credit account's balance is what the customer
-- owes, against credit_limit_cents. What can be spent is the balance (or limit) less approved holds.
create table accounts (
    id                  uuid primary key,
    cardholder_id       uuid not null references cardholders (id),
    kind                text not null check (kind in ('DEBIT', 'CREDIT')),
    currency            char(3) not null default 'USD',
    balance_cents       bigint not null default 0,
    credit_limit_cents  bigint,
    created_at          timestamptz not null default now(),
    check ((kind = 'CREDIT') = (credit_limit_cents is not null))
);

create table cards (
    id             uuid primary key,
    account_id     uuid not null references accounts (id),
    pan_hash       bytea not null unique,          -- HMAC of the card number: how a card is found
    pan_encrypted  bytea not null,                 -- AES-GCM; only the cardholder's own views decrypt it
    bin            char(6) not null,
    last4          char(4) not null,
    expiry         char(4) not null,               -- YYMM, as in field 14
    status         text not null check (status in ('INACTIVE', 'ACTIVE', 'BLOCKED', 'LOST', 'STOLEN', 'CLOSED')),
    label          text,                           -- what a published test card is for
    created_at     timestamptz not null default now()
);

create index cards_account on cards (account_id);

-- Every authorisation request Railnet brings us, approved or not. The acquirer, its reference and trace number
-- identify a request, so a repeat gets the first answer again.
create table authorisations (
    id              uuid primary key,
    card_id         uuid references cards (id),
    account_id      uuid references accounts (id),
    acquirer_id     text not null,
    rrn             text not null,
    stan            text not null,
    mti             char(4) not null,
    amount_cents    bigint not null,
    currency        char(3) not null,
    mcc             char(4),
    merchant        text,
    response_code   char(2) not null,
    auth_code       char(6),
    status          text not null check (status in ('APPROVED', 'DECLINED', 'REVERSED', 'CLEARED')),
    hold_cents      bigint not null default 0,
    created_at      timestamptz not null default now(),
    unique (acquirer_id, rrn, stan)
);

create index authorisations_open_holds on authorisations (account_id) where status = 'APPROVED';
create index authorisations_card on authorisations (card_id, created_at desc);
