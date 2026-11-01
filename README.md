# cardrail

A card payments system, small enough to run on a phone and a laptop, real enough to talk through in depth.

It has every party a card payment touches, each a separate service with its own data and keys:

| Party | Plays | Does |
|---|---|---|
| **Railpay** | Payment gateway | The merchant's API: authorise, capture, void, refund. Hosts the card form, so card numbers never touch the merchant. |
| **Railpay Acquiring** | Acquirer | Signs up merchants, gives them merchant and terminal ids, pays them out after fees, handles chargebacks. |
| **Railnet** | Card network | Routes authorisations between acquirer and issuer as ISO 8583 messages, approves for an issuer that is down (stand-in), clears and settles the day. |
| **Kestrel Bank** | Issuer | Debit and credit cards: limits, statements, interest, card lifecycle, fraud checks and step-up. |
| **Meridian** | Closed loop | Issuer, acquirer and network in one, like American Express, as a second path to compare. |

Sprout, a stock brokerage built earlier, is the first merchant: its customers add money by card.

All amounts are in US dollars, in cents.

## Layout

One repository, one Maven module per service. Modules are tested on their own in CI.

| Module | What it is |
|---|---|
| [`iso8583`](iso8583) | ISO 8583 messages as Railnet speaks them: field specs, bitmaps, encoding, framing over TCP |
| [`common`](common) | What every service shares: errors, request ids, the internal key, ISO 8583 links over TCP, trace reporting |
| [`gateway`](gateway) | Railpay: the merchant API, API keys, idempotency keys |
| [`vault`](vault) | Railpay's card vault and hosted card form |
| [`acquirer`](acquirer) | Railpay Acquiring: merchants, MIDs and terminals, authorisations to the network |
| [`network`](network) | Railnet: BIN routing between acquirers and issuers |
| [`issuer`](issuer) | Kestrel Bank: cards, accounts, holds, the decision on every authorisation |
| [`trace`](trace) | The live trace of every message about a payment |
| [`demoshop`](demoshop) | A demo merchant's server |
| [`host`](host) | Runs services together in one JVM: `acquiring`, `network`, `issuing` or `cell` |
| [`web`](web) | The demo web app (Angular) and the public edge (nginx) |
| [`e2e`](e2e) | End-to-end tests against pre-prod |

## Build

```bash
./mvnw verify
```

Needs Java 21 and Docker (the service tests use a real Postgres). To run the whole system locally, see
[environments](docs/environments.md). See [architecture](docs/architecture.md), [the plan](docs/plan.md) for what comes next and [decisions](docs/decisions.md) for why it is built this way.
