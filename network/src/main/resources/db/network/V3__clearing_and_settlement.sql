-- Phase 3: clearing and settlement.

-- Whether a BIN range's cards are debit or credit: interchange differs (US debit interchange is capped by law).
alter table bin_ranges add column funding text not null default 'DEBIT' check (funding in ('DEBIT', 'CREDIT'));
update bin_ranges set funding = 'CREDIT' where low = '286002';

-- What Railnet charges and passes on, per payment: interchange (the acquirer pays the issuer) and the network's
-- own fee (each side pays it). In basis points (1/100 of a percent) plus a fixed amount.
create table fees (
    name        text primary key,
    bps         int not null,
    fixed_cents int not null
);

insert into fees (name, bps, fixed_cents) values
    ('interchange:DEBIT', 5, 21),      -- the US cap on large banks' debit interchange: 0.05% + 21 cents
    ('interchange:CREDIT', 180, 10),   -- a typical consumer credit rate: 1.80% + 10 cents
    ('scheme:ACQUIRER', 13, 0),        -- the network's own fee, charged to each side
    ('scheme:ISSUER', 13, 0);

-- Every clearing file, in and out, as it was sent: the record of what each party was told.
create table clearing_files (
    seq            bigserial primary key,
    file_id        text not null,
    direction      text not null check (direction in ('IN', 'OUT')),
    member_id      text not null references members (institution_id),
    business_date  date not null,
    content        text not null,
    records        int not null,
    total_cents    bigint not null,
    created_at     timestamptz not null default now(),
    unique (direction, member_id, file_id)
);

create index clearing_files_out on clearing_files (member_id, seq) where direction = 'OUT';

-- Each record the network accepted or rejected, and where it went.
create table clearing_records (
    id                  bigserial primary key,
    in_file_seq         bigint not null references clearing_files (seq),
    out_file_seq        bigint references clearing_files (seq),
    type                text not null check (type in ('SALE', 'CREDIT', 'CHARGEBACK', 'REPRESENTMENT')),
    acquirer_id         text not null,
    issuer_id           text,
    rrn                 text not null,
    amount_cents        bigint not null,
    interchange_cents   bigint not null default 0,
    acquirer_fee_cents  bigint not null default 0,
    issuer_fee_cents    bigint not null default 0,
    status              text not null check (status in ('ROUTED', 'REJECTED')),
    reject_reason       text,
    settlement_id       text,
    created_at          timestamptz not null default now()
);

-- A transaction is presented once: a repeat of a file never moves money twice.
create unique index clearing_records_once on clearing_records (type, acquirer_id, rrn) where status = 'ROUTED';
create index clearing_records_unsettled on clearing_records (id) where status = 'ROUTED' and settlement_id is null;

-- Net settlement: at each cut-off, what each member pays or receives, through the settlement bank.
create table settlements (
    id             text primary key,
    business_date  date not null,
    created_at     timestamptz not null default now()
);

create table settlement_positions (
    settlement_id  text not null references settlements (id),
    member_id      text not null references members (institution_id),
    -- positive: the member pays the settlement bank; negative: the member receives
    net_cents      bigint not null,
    records        int not null,
    primary key (settlement_id, member_id)
);

-- Railnet's books (journals and postings). Accounts: member:<id> (positive: the member owes Railnet),
-- bank:settlement (Railnet's account at the settlement bank), income:scheme-fees.
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
