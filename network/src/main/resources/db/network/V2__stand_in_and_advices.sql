-- Phase 2: stand-in limits are per card, so the network keeps a keyed hash of each card number; reversals find
-- their original by the acquirer's reference; and advices the network owes issuers are stored until delivered.

alter table authorisations
    add column pan_hash    bytea not null default '\x'::bytea,
    add column reversed_at timestamptz;

drop index network_authorisations_rrn;
create index network_authorisations_original on authorisations (acquirer_id, rrn);
create index network_authorisations_stand_in on authorisations (pan_hash, requested_at) where decided_by = 'STAND_IN';

-- Store and forward: what the network approved standing in, and reversals it accepted for an issuer, delivered
-- in order once the issuer answers again and resent until acknowledged. A message holds a card number, so it is
-- kept encrypted.
create table advices (
    id              bigserial primary key,
    issuer_id       text not null references members (institution_id),
    acquirer_id     text not null,
    rrn             text not null,
    mti             char(4) not null,
    message         bytea not null,
    created_at      timestamptz not null default now(),
    attempts        int not null default 0,
    last_error      text,
    acknowledged_at timestamptz
);

create index advices_pending on advices (issuer_id, id) where acknowledged_at is null;
