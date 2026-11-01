-- Phase 2: captures, refunds and reversals.

alter table authorisations
    add column kind           text not null default 'PURCHASE' check (kind in ('PURCHASE', 'REFUND')),
    add column original_rrn   char(12),               -- for a refund: the purchase it gives money back for
    add column captured_cents bigint not null default 0,
    add column captured_at    timestamptz;

alter table authorisations drop constraint authorisations_status_check;
alter table authorisations add constraint authorisations_status_check
    check (status in ('PENDING', 'APPROVED', 'DECLINED', 'UNKNOWN', 'REVERSED', 'CAPTURED'));

create index authorisations_refunds on authorisations (original_rrn) where kind = 'REFUND';

-- Reversals the acquirer owes the network: a void asked for while the network couldn't answer, or a payment whose
-- answer never came (its hold may exist at the issuer). Sent as 0420 advices until acknowledged.
create table reversals (
    id              bigserial primary key,
    rrn             char(12) not null references authorisations (rrn),
    reason          text not null check (reason in ('VOID', 'NO_ANSWER')),
    remaining_cents bigint not null default 0,
    status          text not null default 'PENDING' check (status in ('PENDING', 'DONE')),
    attempts        int not null default 0,
    last_error      text,
    next_attempt_at timestamptz not null default now(),
    created_at      timestamptz not null default now(),
    done_at         timestamptz,
    unique (rrn)
);

create index reversals_due on reversals (next_attempt_at) where status = 'PENDING';
