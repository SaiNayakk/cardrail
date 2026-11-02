-- Phase 5: Meridian cards go straight to Meridian (a closed loop), not through Railnet. Meridian pays the merchant
-- for them itself, so they are in neither Railpay Acquiring's books nor its payouts.
alter table authorisations add column scheme text not null default 'RAILNET' check (scheme in ('RAILNET', 'MERIDIAN'));
