-- Railnet's books.

-- Who is on the network. An acquirer sends authorisations; an issuer answers them.
create table members (
    institution_id  text primary key,       -- field 32 for acquirers, field 100 for issuers
    name            text not null,
    role            text not null check (role in ('ACQUIRER', 'ISSUER'))
);

-- Which issuer a card belongs to, by the first six digits of its number (the BIN).
create table bin_ranges (
    low         char(6) not null,
    high        char(6) not null,
    issuer_id   text not null references members (institution_id),
    product     text not null,              -- what the cards are, for people reading the table
    primary key (low),
    check (low <= high)
);

insert into members (institution_id, name, role) values
    ('400101', 'Railpay Acquiring', 'ACQUIRER'),
    ('286001', 'Kestrel Bank', 'ISSUER');

insert into bin_ranges (low, high, issuer_id, product) values
    ('286001', '286001', '286001', 'Kestrel Bank debit'),
    ('286002', '286002', '286001', 'Kestrel Bank credit');

-- Every authorisation that crossed the network, and who decided it: the issuer, the network standing in for
-- it, or the network itself (no route, a bad message). Clearing matches presentments against this.
create table authorisations (
    id              uuid primary key,
    acquirer_id     text not null,
    rrn             text not null,
    acquirer_stan   char(6) not null,
    issuer_id       text,
    network_stan    char(6),
    bin             char(6) not null,
    last4           char(4) not null,
    mti             char(4) not null,
    amount_cents    bigint not null,
    currency        char(3) not null,
    response_code   char(2),
    auth_code       char(6),
    decided_by      text check (decided_by in ('ISSUER', 'STAND_IN', 'NETWORK')),
    requested_at    timestamptz not null default now(),
    answered_at     timestamptz,
    unique (acquirer_id, rrn, acquirer_stan)
);

create index network_authorisations_rrn on authorisations (rrn);
