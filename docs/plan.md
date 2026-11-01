# Plan

Each phase ends with something that can be shown working.

| Phase | Demo at the end |
|---|---|
| 0. Foundation | The repository builds; the ISO 8583 codec encodes and decodes messages checked byte for byte |
| 1. First authorisation | A test card typed into the hosted form is approved: gateway, acquirer, Railnet (0100/0110 over TCP, routed by BIN) and Kestrel Bank (debit), with every message in a live trace |
| 2. Payment lifecycle | Capture, void (0400 reversal), refund, signed webhooks, idempotency keys, timeouts reversed with 0420, network sign-on and echo (0800), stand-in while Kestrel Bank is down. First public deploy on the laptop |
| 3. Clearing and settlement | A day's clearing files, interchange and network fees, net settlement between members, merchant payouts after MDR, chargebacks and representment, reconciliation across every party |
| 4. Issuer depth | Credit cards with statements, interest and minimum due; issue, activate, block and reissue; limits, velocity and MCC rules; a fraud score; step-up; the cardholder app |
| 5. Closed loop | Meridian cards take the three-party path; a write-up compares it with the four-party one |
| 6. Sprout as a merchant | Sprout customers add money by card through the hosted form, cross-border from dollars to rupees |
| 7. Hardening | Performance (PERF-xx) and chaos suites in pre-prod, with figures drawn from real runs |
| 8. Two cells | Running on the phone and the laptop beside Sprout, replicated both ways, failing over and back |

## Release flow

Branch, pull request, CI green, merge, confirm the merge, annotated tag `vX.Y.Z`, deploy. Unmerged code is
never deployed.
