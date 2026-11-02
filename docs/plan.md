# Plan

Each phase ends with something that can be shown working.

| Phase | Demo at the end | Status |
|---|---|---|
| 0. Foundation | The repository builds; the ISO 8583 codec encodes and decodes messages checked byte for byte | v0.1.0 |
| 1. First authorisation | A test card typed into the hosted form is approved: gateway, acquirer, Railnet (0100/0110 over TCP, routed by BIN) and Kestrel Bank, with every message in a live trace | v0.2.0 |
| 2. Payment lifecycle | Capture, void (0400), refunds, signed webhooks, idempotency keys, timeouts reversed with 0420, echoes (0800), stand-in while Kestrel Bank is down; API v2 | v0.3.0 |
| 3. Clearing and settlement | Clearing files, interchange and network fees, net settlement, merchant payouts after MDR, chargebacks and representments, books that balance | v0.4.0 |
| 4. Issuer depth | Controls, velocity, a fraud score; step-up through a directory and an ACS (API v3); credit statements, interest and minimum due; card lifecycle; the cardholder app | in review |
| 5. Closed loop | Meridian cards take the three-party path; [a comparison](closed-loop.md) of who earns what and who sees what | in review |
| 6. Sprout as a merchant | Sprout customers add money by card through the hosted form | after phase 8: needs cardrail running |
| 7. Hardening | Performance (PERF-xx) and chaos suites in pre-prod | chaos since phase 2; PERF-01 added |
| 8. Two cells | The banks on the phone and the rails on the laptop beside Sprout, each copying the other, failing over and back ([ADR-015](decisions.md#adr-015-two-cells)) | in progress |

## Release flow

Branch, pull request, CI green, merge, confirm the merge, annotated tag `vX.Y.Z`, deploy. Unmerged code is
never deployed.
