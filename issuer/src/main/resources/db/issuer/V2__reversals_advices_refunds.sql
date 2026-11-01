-- Phase 2: refunds, what Railnet approved for us standing in (advices), and reversals.
-- A payment is identified by the acquirer and its retrieval reference: a reversal or an advice finds its original
-- that way, whatever trace number the network gave each message.

alter table authorisations
    add column kind        text not null default 'PURCHASE' check (kind in ('PURCHASE', 'REFUND')),
    add column decided_by  text not null default 'ISSUER' check (decided_by in ('ISSUER', 'STAND_IN')),
    add column reversed_at timestamptz;

alter table authorisations drop constraint authorisations_acquirer_id_rrn_stan_key;
alter table authorisations add constraint authorisations_acquirer_rrn_key unique (acquirer_id, rrn);

comment on column authorisations.stan is 'as the network numbered it to us (field 11)';
