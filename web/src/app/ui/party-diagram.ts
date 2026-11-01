import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-party-diagram',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="diagram-card" aria-label="Card payments network architecture">
      <div class="flow-header">
        <span class="subhead">Core Four-Party Model</span>
        <span class="faint small">Authorisation request & response flow</span>
      </div>

      <div class="nodes-container">
        <!-- 1. Merchant / Demo Shop -->
        <div class="node">
          <div class="node-pill">
            <span class="node-role">Merchant</span>
            <span class="node-title">Demo shop</span>
          </div>
          <span class="node-desc">Presents products, collects tokenised card</span>
        </div>

        <div class="flow-arrow" aria-hidden="true">
          <div class="arrow-line">
            <span class="arrow-label">Checkout API</span>
          </div>
          <svg class="arrow-svg" viewBox="0 0 24 12" fill="none">
            <path d="M0 6h20M16 2l4 4-4 4" stroke="currentColor" stroke-width="1.75" />
          </svg>
        </div>

        <!-- 2. Gateway / Railpay -->
        <div class="node">
          <div class="node-pill">
            <span class="node-role">Payment gateway</span>
            <span class="node-title">Railpay</span>
          </div>
          <span class="node-desc">Hosts card form, validates and tokenises</span>
        </div>

        <div class="flow-arrow" aria-hidden="true">
          <div class="arrow-line">
            <span class="arrow-label">Internal API</span>
          </div>
          <svg class="arrow-svg" viewBox="0 0 24 12" fill="none">
            <path d="M0 6h20M16 2l4 4-4 4" stroke="currentColor" stroke-width="1.75" />
          </svg>
        </div>

        <!-- 3. Acquirer / Railpay Acquiring -->
        <div class="node">
          <div class="node-pill">
            <span class="node-role">Acquirer</span>
            <span class="node-title">Railpay Acquiring</span>
          </div>
          <span class="node-desc">Merchant's bank, translates API to ISO 8583</span>
        </div>

        <div class="flow-arrow" aria-hidden="true">
          <div class="arrow-line">
            <span class="arrow-label">ISO 8583</span>
          </div>
          <svg class="arrow-svg" viewBox="0 0 24 12" fill="none">
            <path d="M0 6h20M16 2l4 4-4 4" stroke="currentColor" stroke-width="1.75" />
          </svg>
        </div>

        <!-- 4. Network / Railnet -->
        <div class="node">
          <div class="node-pill">
            <span class="node-role">Card network</span>
            <span class="node-title">Railnet</span>
          </div>
          <span class="node-desc">Switches messages by card BIN range</span>
        </div>

        <div class="flow-arrow" aria-hidden="true">
          <div class="arrow-line">
            <span class="arrow-label">ISO 8583</span>
          </div>
          <svg class="arrow-svg" viewBox="0 0 24 12" fill="none">
            <path d="M0 6h20M16 2l4 4-4 4" stroke="currentColor" stroke-width="1.75" />
          </svg>
        </div>

        <!-- 5. Issuer / Kestrel Bank -->
        <div class="node">
          <div class="node-pill">
            <span class="node-role">Issuer</span>
            <span class="node-title">Kestrel Bank</span>
          </div>
          <span class="node-desc">Holds cardholder balance, approves or declines</span>
        </div>
      </div>

      <div class="return-flow-note">
        <svg class="return-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M14 11H3.5a2.5 2.5 0 0 1 0-5H12" />
          <polyline points="5.5 8.5 3 11 5.5 13.5" />
        </svg>
        <span>
          Responses travel back along the identical path: Issuer (0110 response) &rarr; Network &rarr; Acquirer &rarr; Gateway &rarr; Shop.
        </span>
      </div>

      <div class="coming-soon-section">
        <div class="closed-loop-header">
          <span class="badge">Coming soon</span>
          <strong>Meridian</strong>
          <span class="muted small">&middot; American Express-style closed loop</span>
        </div>
        <p class="muted small" style="margin-bottom: 0;">
          Closed-loop networks operate as both issuer and acquirer in a single entity, routing payments directly without an external interbank network switch.
        </p>
      </div>
    </div>
  `,
  styles: `
    .diagram-card {
      background-color: var(--surface);
      border: 1px solid var(--line);
      border-radius: var(--radius-lg);
      padding: 24px;
      margin: 24px 0;
    }

    .flow-header {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      margin-bottom: 20px;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--line);
      flex-wrap: wrap;
      gap: 8px;
    }

    .subhead {
      font-size: 0.8125rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--ink-faint);
    }

    .nodes-container {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 8px;
      overflow-x: auto;
      padding: 8px 0 16px;
      -webkit-overflow-scrolling: touch;
    }

    .node {
      flex: 1 1 150px;
      min-width: 130px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .node-pill {
      background-color: var(--surface-subtle);
      border: 1px solid var(--line-strong);
      border-radius: var(--radius);
      padding: 10px 12px;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .node-role {
      font-size: 0.6875rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--accent);
    }

    .node-title {
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--ink);
    }

    .node-desc {
      font-size: 0.75rem;
      color: var(--ink-muted);
      line-height: 1.35;
      padding: 0 2px;
    }

    .flow-arrow {
      flex: 0 0 auto;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding-top: 14px;
      color: var(--ink-faint);
    }

    .arrow-line {
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    .arrow-label {
      font-size: 0.6875rem;
      font-weight: 500;
      color: var(--ink-faint);
      white-space: nowrap;
      margin-bottom: 2px;
    }

    .arrow-svg {
      width: 22px;
      height: 12px;
    }

    .return-flow-note {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 14px;
      background-color: var(--surface-subtle);
      border-radius: var(--radius-sm);
      font-size: 0.8125rem;
      color: var(--ink-muted);
      margin-top: 8px;
    }

    .return-icon {
      width: 14px;
      height: 14px;
      flex-shrink: 0;
      color: var(--ink-faint);
    }

    .coming-soon-section {
      margin-top: 20px;
      padding-top: 16px;
      border-top: 1px dashed var(--line-strong);
    }

    .closed-loop-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 6px;
      flex-wrap: wrap;
    }

    @media (max-width: 768px) {
      .nodes-container {
        flex-direction: column;
        align-items: stretch;
      }

      .node {
        min-width: 100%;
      }

      .flow-arrow {
        padding: 4px 0;
        transform: rotate(90deg);
        height: 24px;
      }

      .arrow-label {
        display: none;
      }
    }
  `,
})
export class PartyDiagram {}
