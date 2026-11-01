-- Phase 2: a card a payment was made with, kept (encrypted, without its security code) so the merchant can refund
-- it later without the shopper typing it again. It belongs to one merchant.
create table cards (
    id             text primary key,               -- crd_ and 24 random characters
    merchant_id    text not null,
    pan_encrypted  bytea not null,
    bin            char(6) not null,
    last4          char(4) not null,
    expiry         char(4) not null,
    created_at     timestamptz not null default now()
);

alter table tokens add column card_id text references cards (id);
