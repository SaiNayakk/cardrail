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
