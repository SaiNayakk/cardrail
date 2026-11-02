-- Phase 4: what a cardholder controls, how Kestrel Bank scores risk, and a card's life.

-- A card's own rules, set by its cardholder in the app.
create table card_controls (
    card_id            uuid primary key references cards (id),
    online_enabled     boolean not null default true,     -- payments where the card isn't present
    single_limit_cents bigint,                            -- the most one payment may be
    daily_limit_cents  bigint,                            -- the most that may be approved in a day (UTC)
    blocked_mccs       text[] not null default '{}',      -- merchant categories refused, e.g. 7995 gambling
    step_up_always     boolean not null default false,    -- every online payment confirmed in the app
    shared             boolean not null default false,    -- a published test card everyone uses: its pace and history
                                                          -- say nothing about one person, so those signals are skipped
    updated_at         timestamptz not null default now()
);

-- A card's life: issued inactive, activated by its cardholder, blocked or reported, replaced by a new number.
alter table cards
    add column activated_at timestamptz,
    add column replaces     uuid references cards (id);

-- Why each decision was made: the risk score and what made it up.
alter table authorisations
    add column fraud_score   int,
    add column risk_reasons  text,
    add column authenticated boolean not null default false;   -- the cardholder confirmed it (step-up)

create index authorisations_card_recent on authorisations (card_id, created_at) where status in ('APPROVED', 'CLEARED');

-- Step-up, Kestrel Bank's ACS (access control server): a payment the cardholder must confirm, with a one-time code
-- shown in their app (as a bank would text it) or by approving it there. Success gives an authentication value the
-- merchant's next authorisation carries; it works once, for that card and no more than that amount.
create table authentications (
    id            text primary key,                  -- acs_...
    card_id       uuid not null references cards (id),
    amount_cents  bigint not null,
    merchant      text not null,
    otp           char(6) not null,
    attempts      int not null default 0,
    status        text not null check (status in ('PENDING', 'Y', 'N')),
    value         text,                              -- the authentication value, once confirmed
    created_at    timestamptz not null default now(),
    expires_at    timestamptz not null,
    decided_at    timestamptz,
    used_at       timestamptz
);

create index authentications_card on authentications (card_id, created_at desc);
