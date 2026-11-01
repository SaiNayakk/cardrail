-- Railpay's card vault. A token stands for a card the cardholder typed into the hosted form. It belongs to one
-- merchant, can be used once, and expires after 15 minutes. The security code is kept only until that use.

create table tokens (
    id             text primary key,               -- tok_ and 24 random characters
    merchant_id    text not null,
    pan_encrypted  bytea not null,
    bin            char(6) not null,
    last4          char(4) not null,
    expiry         char(4) not null,               -- YYMM
    cvc_encrypted  bytea,                           -- dropped on first use (PCI DSS 3.2: never stored after authorisation)
    created_at     timestamptz not null default now(),
    expires_at     timestamptz not null,
    used_at        timestamptz
);

create index tokens_expiry on tokens (expires_at);
