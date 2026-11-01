# Architecture

## Five parties, five sets of books

Each party is its own service with its own database schema and its own keys. They talk only through their
APIs, and the card network part of it only through ISO 8583 messages over TCP, as real ones do.

```mermaid
flowchart LR
  browser([Shopper's browser]) -->|card details, in Railpay's iframe| vault
  browser -->|token| shop[Demo shop's server]
  shop -->|secret key, Idempotency-Key| gw
  subgraph acquiring [acquiring host]
    gw[Railpay gateway] --> vault[Railpay vault]
    gw --> acq[Railpay Acquiring]
  end
  subgraph network [network host]
    rn[Railnet switch]
    trace[Trace]
  end
  subgraph issuing [issuing host]
    kb[Kestrel Bank]
  end
  acq <-->|ISO 8583 over TCP| rn
  rn <-->|ISO 8583 over TCP| kb
```

| Party | Owns | Never does |
|---|---|---|
| Railpay vault | Card numbers typed into the hosted form, encrypted; single-use tokens | Decide anything about a payment |
| Railpay gateway | Merchants, API keys, payments, idempotency keys | Store a card number or security code |
| Railpay Acquiring | Merchants' MIDs and terminals, every authorisation it sent, (phase 3) settlement and fees | Know whether a cardholder has money |
| Railnet | Members, the BIN table, every authorisation that crossed it and who decided it | Hold anyone's money or card data |
| Kestrel Bank | Cardholders, accounts, cards, holds, every decision | Know who the merchant's customer is beyond field 43 |
| Trace | What each party said it sent, masked | Anything a payment depends on |

## An authorisation, end to end

```mermaid
sequenceDiagram
  participant B as Browser
  participant S as Demo shop
  participant V as Vault
  participant G as Gateway
  participant A as Acquirer
  participant N as Railnet
  participant K as Kestrel Bank
  B->>V: card number, expiry, CVV (hosted form)
  V-->>B: tok_... (single use, 15 minutes)
  B->>S: checkout(product, token, orderId)
  S->>G: POST /v1/payments (secret key, Idempotency-Key = order)
  G->>V: reveal token (once; the CVV is then deleted)
  G->>A: authorise (MID, card, amount, RRN)
  A->>N: 0100 (STAN a, RRN, field 32 = acquirer)
  N->>K: 0100 (STAN n, field 33 = Railnet, field 100 = Kestrel)
  Note over K: card known? usable? expiry and CVV2 right?<br/>money for it? place a hold
  K-->>N: 0110 (39 = 00, 38 = approval code)
  N-->>A: 0110 (STAN a restored)
  A-->>G: approved
  G-->>S: payment: authorised
```

- **The card number goes from the browser to the vault and nowhere a merchant can see it.** The shop's page
  holds an iframe served by the vault; the shop gets a token, which it can only use with its own secret key.
- **One reference, every party.** The gateway gives each payment a 12-digit retrieval reference number (RRN,
  field 37), which every party records. The trace is keyed by it.
- **Each link numbers its own messages.** The acquirer's trace number (field 11) is its own; Railnet gives
  what it forwards to an issuer a number of its own and restores the acquirer's on the way back, so numbers
  from different acquirers can't collide on an issuer's link. Responses are matched by field 11 and field 37
  and may come back in any order on a connection ([`IsoLink`](https://github.com/SaiNayakk/cardrail/blob/main/common/src/main/java/app/cardrail/common/iso/IsoLink.java)).
- **A repeat gets the first answer.** The issuer records each request by acquirer, RRN and trace number, the
  network by the acquirer's, and the gateway by idempotency key. A repeat is answered from the record and
  never decided twice.
- **No answer is not a decline.** If Kestrel Bank doesn't answer in 3 s, Railnet answers 91 (issuer
  unavailable). If Railnet doesn't answer the acquirer in 5 s, the payment is UNKNOWN, never declined, because
  the issuer may have placed a hold. (Phase 2 reverses those with 0420.)

## Card data

| Data | Where it exists | How |
|---|---|---|
| Card number | Vault (until the token expires, then deleted), Kestrel Bank | AES-GCM at rest; Kestrel finds a card by an HMAC of its number |
| Security code (CVV2) | Vault until the token's first use; in flight in field 48 | Never stored after authorisation (PCI DSS 3.2). Kestrel derives the right one from its card verification key, so it stores none |
| Everything else | Gateway, acquirer and network keep the BIN and last four | |
| Traces and logs | Masked before they leave a party: BIN and last four, no expiry, CVV, track, PIN or chip data | [`Masking`](https://github.com/SaiNayakk/cardrail/blob/main/iso8583/src/main/java/app/cardrail/iso8583/Masking.java) |

## ISO 8583 as Railnet speaks it

ASCII text fields, a binary bitmap (primary, and secondary when any field above 64 is present), binary PIN,
chip and MAC fields, each message framed by a 2-byte big-endian length. Field numbers, names and lengths follow
ISO 8583:1987, as Visa's and Mastercard's dialects do. Field 48 carries subelements in Mastercard's
tag-length-value style; subelement 92 is the CVV2. See [`MessageSpec`](https://github.com/SaiNayakk/cardrail/blob/main/iso8583/src/main/java/app/cardrail/iso8583/MessageSpec.java).

| Message | Phase | Meaning |
|---|---|---|
| 0100 / 0110 | 1 | Authorisation request and response (dual message: money moves at clearing) |
| 0800 / 0810 | 1 | Network management: echo, sign-on |
| 0120 / 0130 | 2 | Authorisation advice: what the network decided while standing in |
| 0200 / 0210 | 2 | Financial request (single message: authorised and posted at once) |
| 0400 / 0410, 0420 / 0430 | 2 | Reversal request and advice |

## Test cards

| Number | Kind | Outcome |
|---|---|---|
| 2860 0100 0000 0012 | Debit, $5,000 | Approved |
| 2860 0100 0000 0020 | Debit, $5 | Declined over $5: insufficient funds (51) |
| 2860 0100 0000 0038 | Debit, blocked | Restricted card (62) |
| 2860 0100 0000 0046 | Debit, reported lost | Lost card (41) |
| 2860 0100 0000 0053 | Debit, expired January 2025 | Refused by the hosted form; expired card (54) at the issuer |
| 2860 0200 0000 0011 | Credit, $2,000 limit | Approved |

Expiry 12/30 except where it says otherwise. Each card's CVV2 is shown in the demo app (it is derived from the
environment's card verification key, so it differs between environments).

## Running many services on a phone

As in Sprout, services are built separately but run in hosts, one JVM per failure domain
([ADR-005](decisions.md#adr-005-hosts)). Measured in pre-prod (2026-11-02, idle after the E2E suite):

| Host | Services | Memory |
|---|---|---|
| acquiring | gateway, vault, acquirer, demo shop | 168 MB |
| network | Railnet, trace | 155 MB |
| issuing | Kestrel Bank | 138 MB |
| **Total** | | **461 MB** |
