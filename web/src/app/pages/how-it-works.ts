import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-how-it-works',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="guide-header">
        <h1>How card payments work</h1>
        <p class="muted">
          Card payments travel across five parties: the customer's browser, shop, gateway, network, and cardholder bank. Here is how money moves and how errors resolve cleanly.
        </p>
      </div>

      <div class="guide-sections">
        <!-- 1. Authorise and capture -->
        <section class="card concept-card" aria-labelledby="heading-auth-capture">
          <h2 id="heading-auth-capture">Authorise and capture</h2>
          <p>
            Card payments use dual messages. First, the shop requests an authorisation (0100). The bank verifies the card, reserves the funds on the cardholder's credit limit or account, and returns an approval code (0110). Later, the shop captures the payment. Funds transfer during end-of-day settlement.
          </p>

          <div class="diagram-flow" aria-hidden="true">
            <div class="step-box">
              <span class="mono step-tag">0100 Request</span>
              <span class="step-desc">Shop asks bank to reserve money</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box">
              <span class="mono step-tag">0110 Approval</span>
              <span class="step-desc">Bank holds credit balance</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box step-box-accent">
              <span class="mono step-tag">Capture</span>
              <span class="step-desc">Funds transfer during settlement</span>
            </div>
          </div>
        </section>

        <!-- 2. Void -->
        <section class="card concept-card" aria-labelledby="heading-void">
          <h2 id="heading-void">Void</h2>
          <p>
            If an order is cancelled before settlement, the shop sends a void. A void cancels the reserved hold immediately. The cardholder's available credit is restored without waiting for authorizations to expire. No money ever moved, so no interchange fees apply.
          </p>

          <div class="diagram-flow" aria-hidden="true">
            <div class="step-box">
              <span class="mono step-tag">Authorised hold</span>
              <span class="step-desc">Funds held; no money moved</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box step-box-accent">
              <span class="mono step-tag">Void (0420)</span>
              <span class="step-desc">Hold released immediately</span>
            </div>
          </div>
        </section>

        <!-- 3. Refund -->
        <section class="card concept-card" aria-labelledby="heading-refund">
          <h2 id="heading-refund">Refund</h2>
          <p>
            Once a payment is captured, money has moved. To return money, the shop submits a refund. A refund can be partial or for the full amount. Each refund receives its own trace reference and returns money directly to the original card.
          </p>

          <div class="diagram-flow" aria-hidden="true">
            <div class="step-box">
              <span class="mono step-tag">Captured payment</span>
              <span class="step-desc">Settled funds with unique trace</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box step-box-accent">
              <span class="mono step-tag">Refund API</span>
              <span class="step-desc">Money returned to original card</span>
            </div>
          </div>
        </section>

        <!-- 4. When the issuer doesn't answer (stand-in) -->
        <section class="card concept-card" aria-labelledby="heading-stand-in">
          <h2 id="heading-stand-in">When the issuer doesn't answer</h2>
          <p>
            If the cardholder's bank times out or goes offline, the network steps in. Railnet stands in within pre-agreed limits: it approves low-risk payments on behalf of the issuer. Later, when the bank recovers, Railnet delivers an authorisation advice (0120) to record the transaction.
          </p>

          <div class="diagram-flow" aria-hidden="true">
            <div class="step-box">
              <span class="mono step-tag">Network request</span>
              <span class="step-desc">Issuer does not answer in time</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box step-box-dashed">
              <span class="mono step-tag">Railnet stand-in</span>
              <span class="step-desc">Network approves within limits</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box">
              <span class="mono step-tag">0120 Advice</span>
              <span class="step-desc">Issuer updated once online</span>
            </div>
          </div>
        </section>

        <!-- 5. When the network doesn't answer (reversal) -->
        <section class="card concept-card" aria-labelledby="heading-reversal">
          <h2 id="heading-reversal">When the network doesn't answer</h2>
          <p>
            If the gateway sends an authorisation request to the network but receives no answer within the timeout, the outcome is unknown. To protect the cardholder, the gateway issues an automatic reversal request (0400). If the network is unreachable, it repeats as a reversal advice (0420) until acknowledged (0430). The customer is never charged for a payment that failed.
          </p>

          <div class="diagram-flow" aria-hidden="true">
            <div class="step-box">
              <span class="mono step-tag">Network timeout</span>
              <span class="step-desc">Gateway receives no reply</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box step-box-accent">
              <span class="mono step-tag">0400 Reversal</span>
              <span class="step-desc">Cancels pending authorization</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box">
              <span class="mono step-tag">0420 Advice</span>
              <span class="step-desc">Repeats until 0430 confirms</span>
            </div>
          </div>
        </section>

        <!-- 6. After the payment: clearing and settlement -->
        <section class="card concept-card" aria-labelledby="heading-clearing">
          <h2 id="heading-clearing">After the payment: clearing and settlement</h2>
          <p>
            Authorisation holds the funds; clearing and settlement move the money. The acquirer bundles captured purchases into a clearing file for Railnet. Railnet calculates interchange (the fee the acquirer pays the cardholder's bank) and its own network fee, and notifies the issuer to post the charge. At cut-off, all net obligations are settled through reserve accounts at a central settlement bank. The shop receives a payout of settled sales less its fee (2.5% MDR).
          </p>

          <div class="clearing-diagram" aria-hidden="true">
            <div class="clearing-row">
              <div class="step-box">
                <span class="mono step-tag">Acquirer</span>
                <span class="step-desc">Sends clearing file</span>
              </div>
              <span class="flow-arrow">&rarr;</span>
              <div class="step-box step-box-accent">
                <span class="mono step-tag">Railnet</span>
                <span class="step-desc">Computes interchange &amp; fees</span>
              </div>
              <span class="flow-arrow">&rarr;</span>
              <div class="step-box">
                <span class="mono step-tag">Issuer</span>
                <span class="step-desc">Posts purchase to account</span>
              </div>
            </div>
            <div class="settlement-bridge">
              <div class="settlement-box">
                <span class="mono step-tag">Settlement Bank</span>
                <span class="step-desc">Transfers net balances between member reserve accounts</span>
              </div>
            </div>
          </div>
        </section>

        <!-- 7. Disputes -->
        <section class="card concept-card" aria-labelledby="heading-disputes">
          <h2 id="heading-disputes">Disputes</h2>
          <p>
            A cardholder can dispute a purchase through their bank if they did not recognise the charge, never received their items, found them not as described, or were charged twice. The issuer credits the cardholder and issues a chargeback to the shop. The merchant can accept the dispute to close it, or challenge it by submitting evidence such as delivery signatures and proof of fulfillment. If the evidence confirms the sale was valid, the dispute is won and the money is returned to the shop.
          </p>
        </section>

        <!-- 8. Confirming with your bank (step-up) -->
        <section class="card concept-card" aria-labelledby="heading-step-up">
          <h2 id="heading-step-up">Confirming with your bank (step-up)</h2>
          <p>
            When an online payment triggers security checks, Kestrel Bank asks for step-up confirmation. The shop shows the bank's secure page in a challenge frame. The cardholder checks their Kestrel Bank app for a one-time code or approves the prompt directly. Once confirmed, the bank verifies the authentication and the payment is submitted for authorisation with cryptographic proof.
          </p>

          <div class="diagram-flow" aria-hidden="true">
            <div class="step-box">
              <span class="mono step-tag">Shop</span>
              <span class="step-desc">Requests checkout</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box">
              <span class="mono step-tag">Railpay</span>
              <span class="step-desc">Forwards request</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box">
              <span class="mono step-tag">Railnet directory</span>
              <span class="step-desc">Checks 3-D Secure</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box step-box-accent">
              <span class="mono step-tag">Kestrel Bank's page</span>
              <span class="step-desc">Challenge frame loaded</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box step-box-accent">
              <span class="mono step-tag">Code in the app</span>
              <span class="step-desc">Cardholder approves code</span>
            </div>
            <span class="flow-arrow">&rarr;</span>
            <div class="step-box">
              <span class="mono step-tag">Payment authorised</span>
              <span class="step-desc">Authorised with proof</span>
            </div>
          </div>
        </section>

        <!-- 9. Your card, your rules -->
        <section class="card concept-card" aria-labelledby="heading-card-rules">
          <h2 id="heading-card-rules">Your card, your rules</h2>
          <p>
            Cardholders stay in control of their spending through the Kestrel Bank app. You can switch online payments on or off, set per-payment and daily spend limits, and block high-risk merchant categories like gambling or money transfers. If your card is misplaced, freeze it instantly to prevent unauthorised charges. If it is lost or stolen, cancel it immediately to receive a new card number.
          </p>
        </section>

        <div class="guide-footer-cta">
          <a routerLink="/shop" class="btn">Try a payment in the shop &rarr;</a>
          <a routerLink="/card" class="btn btn-secondary">Cardholder app</a>
          <a routerLink="/dashboard" class="btn btn-secondary">Shop dashboard</a>
          <a routerLink="/books" class="btn btn-secondary">View books</a>
          <a routerLink="/traces" class="btn btn-secondary">Inspect live traces</a>
        </div>
      </div>
    </main>
  `,
  styles: `
    .guide-header {
      margin: 16px 0 24px;
      max-width: 720px;
    }

    .guide-sections {
      display: flex;
      flex-direction: column;
      gap: 20px;
      max-width: 820px;
    }

    .concept-card {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .concept-card h2 {
      font-size: 1.125rem;
      margin-bottom: 2px;
    }

    .concept-card p {
      margin-bottom: 4px;
      color: var(--ink);
    }

    .diagram-flow {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 14px;
      background-color: var(--surface-subtle);
      border: 1px solid var(--line);
      border-radius: var(--radius-sm);
      overflow-x: auto;
      margin-top: 6px;
    }

    .step-box {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 140px;
      flex: 1 1 0;
      padding: 8px 10px;
      background-color: var(--surface);
      border: 1px solid var(--line-strong);
      border-radius: var(--radius-sm);
    }

    .step-box-accent {
      border-color: var(--accent);
      background-color: var(--accent-soft);
    }

    .step-box-dashed {
      border: 1.5px dashed var(--accent);
      background-color: var(--accent-soft);
    }

    .step-tag {
      font-size: 0.75rem;
      font-weight: 600;
      color: var(--accent);
    }

    .step-desc {
      font-size: 0.75rem;
      color: var(--ink-muted);
    }

    .flow-arrow {
      color: var(--accent);
      font-weight: 600;
      padding: 0 2px;
    }

    .clearing-diagram {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 12px 14px;
      background-color: var(--surface-subtle);
      border: 1px solid var(--line);
      border-radius: var(--radius-sm);
      overflow-x: auto;
      margin-top: 6px;
    }

    .clearing-row {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .settlement-bridge {
      display: flex;
      justify-content: center;
      padding-top: 4px;
    }

    .settlement-box {
      display: flex;
      flex-direction: column;
      gap: 4px;
      max-width: 420px;
      width: 100%;
      text-align: center;
      padding: 8px 12px;
      background-color: var(--surface);
      border: 1.5px dashed var(--line-strong);
      border-radius: var(--radius-sm);
    }

    .guide-footer-cta {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-top: 12px;
      flex-wrap: wrap;
    }
  `,
})
export class HowItWorks {}
