-- Phase 4: step-up. A payment the card's bank wants the cardholder to confirm waits as REQUIRES_ACTION while the
-- shopper does, then is authorised again with the proof. The first attempt's reference is kept for its trace.

alter table payments
    add column authentication_issuer text,
    add column authentication_id     text,
    add column challenge_path        text,
    add column first_rrn             char(12);

alter table payments drop constraint payments_status_check;
alter table payments add constraint payments_status_check
    check (status in ('PENDING', 'AUTHORISED', 'DECLINED', 'UNKNOWN', 'CAPTURED', 'VOIDED', 'REVERSED', 'FAILED', 'REQUIRES_ACTION'));
