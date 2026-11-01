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

        <div class="guide-footer-cta">
          <a routerLink="/shop" class="btn">Try a payment in the shop &rarr;</a>
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
