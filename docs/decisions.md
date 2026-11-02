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

## ADR-010: Clearing by file, settlement net, every party with its own books { #adr-010-clearing }

**Decision.** After authorisation, money moves the way it does on real card networks: in batches. The acquirer
sends Railnet a clearing file (fixed-width records, a trailer with a count and a total) of what its merchants
captured and refunded. Railnet checks every record against an authorisation it approved, for no more than was
approved, works out interchange (the US debit cap, 0.05% + 21 cents; credit 1.80% + 10 cents) and its own fee,
and passes each record to the other side. At a cut-off, each member's position is settled net through the
settlement bank: one payment each way, however many transactions. Every party keeps its own double-entry books,
and each book always adds up to zero.

**Why.** Dual message (authorise now, clear later) is how card payments really work, and it is where the
interesting failures are: a capture for less than was authorised, a refund presented twice, a file cut short, a
chargeback a month later. Records carry the reference (RRN) and the BIN and last four, never the card number:
every party finds the authorisation a record belongs to by reference. Separate books per party, compared only
through what they publish (RECON-01, RECON-02), are the only honest way to show that nothing was lost between
them.

**Lost.** Simplifications: one currency; one acquirer and one issuer; interchange by debit or credit only, not by
merchant category or card product; a representment ends the dispute (no pre-arbitration or arbitration); members
share one internal key (each would have its own credentials). In pre-prod the day runs fast: files every 5 s,
settlement every 15 s.

## ADR-011: Merchants are paid only what has settled { #adr-011-payouts }

**Decision.** The acquirer pays a merchant, after each settlement it books, the sales that settled less refunds,
its fee (MDR, 2.5% by default) and chargebacks, plus chargebacks won back. Never a negative amount: a merchant
that owes after chargebacks has it taken from the next payout.

**Why.** Paying before the network pays would have the acquirer lend money to every merchant; paying from settled
records only means a payout always equals money that arrived. The MDR is the acquirer's income; interchange and
Railnet's fee are its costs, so on a small debit payment its margin can be negative, which is real and worth seeing
in the books.

## ADR-012: Risk is explainable, and the cardholder sets rules too { #adr-012-risk }

**Decision.** After "is this the real card", Kestrel Bank applies, in order: the cardholder's own controls (online on
or off, a limit per payment and per day, blocked merchant categories), velocity (more than five approvals in ten
minutes), and a fraud score of a few named signals added up (far above this card's usual spend, $500 or more, a burst
of payments, a risky kind of merchant, a large first payment). Each authorisation records its score and reasons. At
80 it is declined as suspected fraud (59); from 50 an online payment must be confirmed by the cardholder (step-up).

**Why.** A score that can be explained can be shown to the cardholder ("declined: 3 payments in 10 minutes") and
argued about in an interview; an opaque model can't be checked by reading the code. The published test cards are
shared by every visitor, so signals about one person's habits are skipped for them, and they can't be changed;
visitors open a card of their own to try controls, freezing and losing a card.

**Lost.** A real issuer's model would weigh hundreds of signals and learn from chargebacks.

## ADR-013: Step-up through a directory, as in 3-D Secure; a new API version for it { #adr-013-step-up }

**Decision.** When Kestrel Bank answers 1A (authenticate the cardholder first), Railpay asks Railnet's directory
server, which finds the issuer by BIN and asks its ACS. The ACS shows the shopper a page of its own, where they type
the one-time code their Kestrel Bank app shows (or approve in the app). Success gives an authentication value: an
HMAC of the authentication, the card and the amount under a key only Kestrel Bank holds. Railpay authorises again
with it in field 48 (security level 212, value, id) and a new reference; Kestrel checks it and that it is unused, and
skips the step-up. Merchant API v3 returns `requires_action` with the page to show and a `confirm` call; v2 declines
such payments and shows v3's as pending (ADR-006).

**Why.** It is how online card payments are authenticated in Europe and India, and increasingly in the US: the
merchant never sees how the bank authenticates, the network only routes, and the proof is bound to one card and
amount and spent once, so a captured value can't be replayed. Adding a status that v2 clients were promised they'd
never see is a breaking change, so it is a new version.

**Lost.** No frictionless flow with device data, no challenge in the browser's native sheet, no liability shift in
clearing.

## ADR-014: Credit card statements with a grace period { #adr-014-statements }

**Decision.** Each credit account's cycle ends with a statement. Interest is the average daily balance times the APR
(24.99%) for 30 days, charged only if the previous statement wasn't paid in full by its due date; the minimum due is
the balance under $25, otherwise the greater of $25 and 1% of the balance plus the interest and fees just charged; a
$25 late fee when the minimum wasn't paid. Cycles are 30 days (10 minutes in pre-prod), and interest always counts a
cycle as 30 days.

**Why.** That is how US credit cards charge, and the grace period is the part most people don't know: pay the
statement in full and purchases cost nothing.

**Lost.** No promotional rates, no cash advances (they'd have no grace period), no penalty APR.

## ADR-015: Two cells split by institution, each copying the other { #adr-015-two-cells }

**Decision.** Both cells serve, and they split by institution: the phone (cell a) runs the banks, Kestrel Bank and
Meridian, in one JVM (`banks`); the laptop (cell b) runs everything between the merchant and the banks, in one JVM
(`rails`): gateway, vault, acquirer, demo shop, Railnet and the trace. Every card payment crosses both cells: Railnet
sends ISO 8583 to Kestrel Bank over the home network, as a network reaches a bank over a private line.

Each cell's `cardrail` database (its own database and role inside Sprout's Postgres) owns the schemas of what it
runs and publishes them; it subscribes to the other cell's. Each cell so holds a whole copy, and no table is written
in two places, so the two-way replication has no conflicts to resolve. Flyway's history stays out of it: each cell
migrates every schema, owned or copied, before either starts a release, because logical replication carries rows,
not DDL.

When a cell is lost, the other takes it over: it stops copying, moves the copied schemas' sequences past every
copied id (replication doesn't carry sequences), publishes everything, and restarts as the all-in-one `cell` host on
its now-whole database. On the phone it waits until Sprout's own takeover has finished, so two JVMs never start at
once at the phone's worst memory moment. A cell that comes back while the other holds its institutions serves
nothing until it is handed them back (runbook).

**Why.** It is how card payments are actually spread: a bank's systems don't live in the network's data centre.
It makes the two cells serve at once without the hard problem of active-active writes, and it fits the phone: the
banks use about 180 MB, and the whole system in one JVM about 300 MB, under the 400 MB the phone can give. A short
loss of the phone needs no takeover at all: Railnet stands in for Kestrel Bank within its limits and tells it
afterwards (ADR-007).

**Lost.**
- Every payment needs both cells, or Railnet's stand-in; a payment is as available as the two cells together.
- A takeover restarts the survivor's own half too: about a minute without payments.
- Writes in the seconds before a cell was lost may not have reached the other's copy. The acquirer's reversals
  (ADR-008) and the advices (ADR-007) are kept in the rails' schemas, so the ones lost are the bank's own last
  records; the network's settlement figures show any difference.
- A cell that can't see the other but is still running (a split) could be taken over while it serves. The takeover
  requires the other cell silent on both the home network and its public address, which makes that rare, not
  impossible.
