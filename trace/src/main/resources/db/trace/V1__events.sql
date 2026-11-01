-- What each party said it sent, for the live trace. Card data arrives masked; nothing here is sensitive.
create table events (
    id          bigserial primary key,
    trace_key   text not null,          -- the payment's retrieval reference (field 37)
    reporter    text not null,
    from_party  text not null,
    to_party    text not null,
    kind        text not null check (kind in ('iso8583', 'note')),
    mti         char(4),
    summary     text not null,
    details     jsonb not null default '{}',
    at          timestamptz not null,   -- when the party sent it, by its clock
    received_at timestamptz not null default now()
);

create index events_trace on events (trace_key, at, id);
create index events_received on events (received_at);
