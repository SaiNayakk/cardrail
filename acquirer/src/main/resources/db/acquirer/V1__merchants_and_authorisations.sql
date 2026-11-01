-- Railpay Acquiring's books. Money in US cents.

-- A merchant the acquirer has signed up. The MID (field 42) is how the network and issuers know it.
create table merchants (
    id          uuid primary key,
    mid         char(15) not null unique,
    name        text not null,
    mcc         char(4) not null,               -- merchant category code (field 18)
    city        text not null,
    country     char(2) not null default 'US',
    mdr_bps     int not null default 250,       -- merchant discount rate in basis points: 250 = 2.50%
    status      text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    created_at  timestamptz not null default now()
);

-- A terminal (field 41). An online merchant gets a virtual one.
create table terminals (
    tid          char(8) primary key,
    merchant_id  uuid not null references merchants (id),
    kind         text not null check (kind in ('ECOMMERCE', 'POS')),
    created_at   timestamptz not null default now()
);

create sequence mid_seq start 1;
create sequence tid_seq start 1;

-- Every authorisation the acquirer sent to the network. The RRN is the gateway's reference for the payment.
create table authorisations (
    id              uuid primary key,
    merchant_id     uuid not null references merchants (id),
    tid             char(8) not null,
    rrn             char(12) not null unique,
    stan            char(6) not null,
    transmitted     char(10) not null,          -- field 7, MMDDhhmmss UTC: needed to reverse it
    bin             char(6) not null,
    last4           char(4) not null,
    amount_cents    bigint not null,
    currency        char(3) not null,
    response_code   char(2),
    auth_code       char(6),
    status          text not null check (status in ('PENDING', 'APPROVED', 'DECLINED', 'UNKNOWN', 'REVERSED')),
    created_at      timestamptz not null default now(),
    answered_at     timestamptz
);
