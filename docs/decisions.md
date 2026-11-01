# Decisions

Architecture decision records: the choice, why, and what lost. Newest last.

## ADR-001: One repository, a Maven module per service { #adr-001-monorepo }

**Decision.** Every service, the shared libraries and the contracts live in one repository as Maven modules
sharing one version. CI builds and tests each module on its own, only when it or what it is built from changed.
A release is an annotated tag `vX.Y.Z`.

**Why.** Sprout used one repository per service. Every change that crossed services became a chain of releases:
contracts first, then each service, then each host's manifest. Most real changes cross services. One repository
makes such a change one pull request, while per-module CI and versioned contracts keep the boundaries honest.

**Lost.** A service can't be released on its own schedule. Fine for one person; the module boundaries mean a
service could still be split out later.

## ADR-002: Java 21, Spring Boot 3, plain JDBC, Postgres 18 { #adr-002-stack }

**Decision.** The same stack as Sprout: Java 21 on Spring Boot 3, plain JDBC with Flyway, Postgres 18. The web
app is Angular 21.

**Why.** It is what banks and card networks run, Sprout's patterns carry over unchanged, and plain JDBC keeps
every query visible and saves memory on the phone.

**Lost.** A smaller runtime (Go) would use far less memory.

## ADR-003: Our own ISO 8583 codec { #adr-003-iso8583 }

**Decision.** Messages are encoded by a small codec written here (the `iso8583` module), not by jPOS or another
library. Railnet's dialect: ASCII text fields, a binary bitmap, binary PIN, chip and MAC fields, 2-byte length
framing on TCP.

**Why.** The point of the project is to understand the network layer. A codec is a few hundred lines; writing it
means knowing exactly what a bitmap, an LLVAR or a secondary bitmap is. Its tests check bytes written out by hand,
so they don't trust the codec's own arithmetic.

**Lost.** jPOS supports every dialect and packager. Railnet needs one.

## ADR-004: US dollars, global card rules { #adr-004-usd }

**Decision.** Card accounts, authorisations and settlement are in US dollars (ISO 4217 `840`), in cents. Step-up
(3-D Secure style) is asked for when the issuer judges the risk high, as in the US, not on every online payment.

**Why.** It matches how Visa, Mastercard and American Express work in their largest market.

**Lost.** Sprout's wallet is in rupees, so its card top-ups are cross-border: the network converts the amount
(fields 6, 10 and 51). That is a feature to build, not a shortcut, in the Sprout phase.

## ADR-005: Three hosts by failure domain { #adr-005-hosts }

**Decision.** As in Sprout, services are built separately but run together in hosts, one JVM each:
`acquiring` (gateway, vault, acquirer), `network` (switch, closed loop, trace) and `issuing` (the issuer). An
all-in-one `cell` host runs everything in one JVM as the other cell's standby.

**Why.** The issuer gets its own JVM so the switch can be tested standing in while the issuer is down, which is
the network's most interesting failure. Grouping the rest keeps the phone's share to about 550 MB plus a 300 MB
standby.

**Lost.** The closed loop shares a JVM with the switch it is meant to be independent of.

## ADR-006: A breaking change is a new version, served beside the old { #adr-006-api-versions }

**Decision.** Railpay's merchant API is versioned in its path. Version 1 stays exactly as published
(`contracts/gateway-v1.yaml`); version 2 adds capture, void, refunds and webhooks, and captures at once by default.
CI compares every contract with `main` (oasdiff) and fails on a breaking change, so the only way to break one is a
new file and a new path.

**Why.** Phase 2 changed what an existing call returns: capturing by default turns "authorised" into "captured",
and payments gained statuses (voided, reversed) a version 1 client was promised it would never see. The contract
check failed on exactly that. A client written against version 1 keeps getting version 1: payments it makes are
never captured, and a state it doesn't know is shown as the nearest one it does (captured as authorised, voided
and reversed as failed).

**Lost.** Two views of the same payment to keep correct. Cheap here; a real gateway keeps several for years.

## ADR-007: Stand-in within the issuer's limits, told by advices that are never lost { #adr-007-stand-in }

**Decision.** When an issuer is down (two missed echoes) or doesn't answer in 3 s, Railnet decides for it:
approve a card that passes Luhn and hasn't expired, under a limit per payment ($100), per card per day (3 and
$200), as Kestrel Bank set. Every stand-in approval is owed to the issuer as an 0120 advice; every payment the
network forwarded but then declined (the issuer may approve it late) is owed as an 0420 reversal. Owed advices are
stored encrypted and delivered in order, one at a time, until acknowledged.

**Why.** A card network that is down when an issuer is down loses everyone's sales; real networks stand in. But
the network can't see balances or check a CVV2, so it takes only small risks, and the issuer, bound by what was
approved, must hear about every one even if the network restarts in between. In order, because a reversal must
never overtake what it reverses. CHAOS-01 checks it with the issuer frozen.

**Lost.** A stand-in approval can overdraw an account (the issuer's risk, as in real networks). Limits are
configuration, not a table each issuer can change.

## ADR-008: No answer is never a decline; it is a reversal until someone hears it { #adr-008-reversals }

**Decision.** A payment whose answer never came is UNKNOWN, never declined. The acquirer owes the network an
0420 reversal for it and sends it with growing gaps until acknowledged; the gateway reports the payment as
reversed once that happens. Reversals carry the original's reference (field 37) and field 90, not the card number:
the network and the issuer find the original by reference. A reversal of something never seen is remembered, so an
original arriving late is declined, not approved and held for nothing.

**Why.** A timed-out request may still arrive: the issuer may approve it and hold the money for a payment the
merchant thinks failed. Guessing "declined" leaves that hold; guessing "approved" ships goods unpaid. Reversing
until heard makes every outcome end the same way, whatever was lost. CHAOS-02 freezes the network mid-payment: the
original is approved after all, then reversed.

**Lost.** A cardholder may see a hold for a few seconds or minutes before it is released.

## ADR-009: Webhooks through an outbox, signed, at least once { #adr-009-webhooks }

**Decision.** Each change of a payment or refund writes its webhook event in the same transaction. A sender
delivers events with growing gaps (10 s doubling to an hour) for up to three days, signed with HMAC-SHA256 over
the timestamp and body. The demo shop checks the signature and refuses deliveries more than five minutes old.

**Why.** A merchant that ships goods on a webhook must never miss one, nor be fooled by a forged or replayed one.
The outbox makes "the payment changed" and "the merchant will be told" one atomic fact; the signature and
timestamp make a captured delivery useless later.

**Lost.** A merchant may get an event twice and must use its id to ignore the repeat.
