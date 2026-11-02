# Testing

| Level | Where | Runs |
|---|---|---|
| Unit | Each module's `src/test` | CI, for each module that changed |
| Integration | Each service's tests, against a real Postgres 18 (Testcontainers) and real TCP sockets | CI, for each module that changed |
| End to end | [`e2e`](https://github.com/SaiNayakk/cardrail/tree/main/e2e), against pre-prod through its public edge only | CI, every pull request |

The codec's tests check bytes written out by hand, so they don't trust the codec's own arithmetic. Service tests
fake only the party on the other side of a socket or an HTTP call (a fake issuer behind Railnet, a fake network
behind the acquirer), never their own database.

## End-to-end tests

Each pays the demo shop the way the web app does (the hosted form's token endpoint, then the shop's checkout)
and checks the result and its trace.

| Id | Checks |
|---|---|
| E2E-01 | An approved payment's trace shows every party in order: merchant, gateway, acquirer, 0100 to Railnet, 0100 to Kestrel Bank, 0110 back each way, the acquirer's answer, the gateway's |
| E2E-02 | Each test card gets its published outcome (51, 62, 41, 00 on credit) |
| E2E-03 | The expired card is refused by the hosted form before it reaches anyone |
| E2E-04 | The same checkout sent twice (a double click, a retry after a lost answer) charges once |
| E2E-05 | No full card number, expiry or security code appears in a trace |
| E2E-06 | The edge never forwards internal or actuator routes |
| E2E-07 | The merchant API refuses a call without a secret key |
| E2E-08 | A refund is its own authorisation (processing code 20) with its own trace, and can't exceed what was paid |
| E2E-09 | The shop is told by a signed webhook, which it checks before believing |
| E2E-10 | Version 1 of the merchant API is still answered at its first address, and version 2 at its own |

## Clearing, settlement and reconciliation

Pre-prod runs the clearing day fast (files every 5 s, settlement every 15 s), so these wait up to two minutes.

| Id | Checks |
|---|---|
| CLEAR-01 | A captured payment is presented with the merchant's fee, cleared by Railnet with interchange, and posted to the cardholder |
| SETTLE-01 | Once settled, the shop is paid what it sold less refunds, fees and disputes, to the cent |
| DISPUTE-01 | A cardholder disputes a purchase; the chargeback reaches the shop; the shop answers with evidence; the representment reaches the cardholder's bank and the shop wins |
| RECON-01 | Every party's books add up to zero |
| RECON-02 | Once the day is quiet, everything cleared has been settled and booked by both sides: nothing is due anywhere |

## Chaos experiments

They pause real containers in pre-prod, then check that nobody is charged for a payment the merchant was told
failed, and no approval the merchant was told about is lost.

| Id | Hypothesis |
|---|---|
| CHAOS-01 | Kestrel Bank freezes: Railnet stands in within its limits ($19.99 approved, $250 declined 91), and once Kestrel answers again it receives the 0120 advice, and an 0420 for any forwarded payment stand-in declined |
| CHAOS-02 | Railnet freezes mid-payment: the payment is unknown, never declined; once Railnet is back the original goes through after all and the acquirer's 0420 reverses it at the issuer; the merchant sees "reversed" |
| CHAOS-03 | After every experiment, payments work normally again, with no stand-in |
