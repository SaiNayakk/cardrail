-- Phase 3: Kestrel Bank's books, clearing, settlement and disputes.

-- Double-entry books, debits positive. Accounts:
--   cardholder:<account id>  positive: the cardholder owes us (credit); negative: we hold their money (debit)
--   bank:reserves            our own money
--   due:railnet              positive: Railnet owes us; negative: we owe Railnet
--   income:interchange, expense:scheme-fees
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

-- What clearing told us about a purchase: how much, and which merchant and terminal (a chargeback names them).
alter table authorisations
    add column cleared_cents bigint not null default 0,
    add column cleared_at    timestamptz,
    add column merchant_id   text,
    add column terminal_id   text;

alter table authorisations drop constraint authorisations_status_check;
alter table authorisations add constraint authorisations_status_check
    check (status in ('APPROVED', 'DECLINED', 'REVERSED', 'CLEARED', 'EXPIRED'));

-- Where Kestrel Bank got to in Railnet's files and settlement reports.
create table cursors (
    name   text primary key,
    value  text not null
);

-- A cardholder disputes a cleared purchase: they get the money back at once, and Kestrel Bank charges it back to
-- the merchant's acquirer through Railnet. If the merchant answers with evidence (a representment), the cardholder
-- is charged again.
create table disputes (
    id              text primary key,             -- dsp_...
    authorisation_id uuid not null references authorisations (id),
    account_id      uuid not null references accounts (id),
    acquirer_id     text not null,
    rrn             text not null,
    amount_cents    bigint not null check (amount_cents > 0),
    reason_code     char(4) not null,
    status          text not null check (status in ('OPEN', 'CHARGED_BACK', 'REPRESENTED')),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (acquirer_id, rrn)
);
