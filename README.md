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

## Build

```bash
./mvnw verify
```

Needs Java 21. See [the plan](docs/plan.md) for what comes next and [decisions](docs/decisions.md) for why it is built this way.
