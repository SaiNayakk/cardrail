import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { disputeReasonLabel, disputeStatusLabel } from '../core/disputes';
import { Loader } from '../core/loader';
import { formatCents } from '../core/money';
import { computePayoutSummary } from '../core/payouts';
import { messageOf } from '../core/problem';
import {
  Dispute,
  DisputeListResponse,
  Payout,
  PayoutListResponse,
} from '../core/types';
import { OrdersList } from '../ui/orders-list';

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, FormsModule, OrdersList],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="dashboard-header">
        <div>
          <h1>Shop dashboard</h1>
          <p class="muted">
            The demo shop's Railpay dashboard: settled payouts, cardholder disputes, and recent transactions.
          </p>
        </div>
        <button
          type="button"
          class="btn btn-secondary btn-sm"
          [disabled]="loadingAny()"
          (click)="reloadAll()"
        >
          {{ loadingAny() ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>

      <!-- Financial Summary Row -->
      <section class="summary-section" aria-labelledby="summary-heading">
        <h2 id="summary-heading" class="sr-only">Financial summary</h2>
        <div class="summary-grid">
          <div class="card stat-card">
            <span class="stat-label">Gross sales</span>
            <span class="stat-value mono num">{{ formatMoney(summary().sales) }}</span>
            <span class="stat-sub muted small">Total captured sales</span>
          </div>

          <div class="card stat-card">
            <span class="stat-label">Refunds</span>
            <span class="stat-value mono num">{{ formatMoney(summary().refunds) }}</span>
            <span class="stat-sub muted small">Returned to cardholders</span>
          </div>

          <div class="card stat-card">
            <span class="stat-label">Railpay fees</span>
            <span class="stat-value mono num">{{ formatMoney(summary().fees) }}</span>
            <span class="stat-sub muted small">Merchant fee (2.5% MDR)</span>
          </div>

          <div class="card stat-card stat-card-accent">
            <span class="stat-label">Net paid out</span>
            <span class="stat-value mono num">{{ formatMoney(summary().netPaid) }}</span>
            <span class="stat-sub muted small">Deposited into shop account</span>
          </div>
        </div>
      </section>

      <!-- Payouts Table -->
      <section class="card payouts-card" aria-labelledby="payouts-heading">
        <div class="section-head">
          <h2 id="payouts-heading" class="section-title">Payouts</h2>
          <span class="badge">{{ payoutsList().length }} settled</span>
        </div>
        <p class="payout-explainer muted small">
          A payout is everything settled since the last one, less Railpay's fee (2.5%) and chargebacks.
        </p>

        @if (payoutsLoader.loading() && payoutsList().length === 0) {
          <p class="muted small">Loading payouts…</p>
        } @else if (payoutsLoader.error(); as err) {
          <div class="card-error" role="alert">
            <p class="error-msg">{{ err }}</p>
            <button type="button" class="btn btn-secondary btn-sm" (click)="payoutsLoader.reload()">
              Retry
            </button>
          </div>
        } @else if (payoutsList().length === 0) {
          <p class="muted small">
            No payouts recorded yet. Captured payments clear in the background and appear here once settled.
          </p>
        } @else {
          <div class="table-wrap">
            <table aria-label="Settled payouts table">
              <thead>
                <tr>
                  <th scope="col">Date</th>
                  <th scope="col">Sales</th>
                  <th scope="col">Refunds</th>
                  <th scope="col">Fees</th>
                  <th scope="col">Disputes</th>
                  <th scope="col">Amount paid</th>
                </tr>
              </thead>
              <tbody>
                @for (payout of payoutsList(); track payout.id) {
                  <tr>
                    <td class="mono small">{{ payout.createdAt | date: 'mediumDate' }}</td>
                    <td class="mono num">{{ formatMoney(payout.sales) }}</td>
                    <td class="mono num">{{ formatMoney(payout.refunds) }}</td>
                    <td class="mono num">{{ formatMoney(payout.fees) }}</td>
                    <td class="mono num">{{ formatMoney(payout.disputes) }}</td>
                    <td class="mono num font-semibold">{{ formatMoney(payout.amount) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <!-- Disputes Section -->
      <section class="card disputes-card" aria-labelledby="disputes-heading">
        <div class="section-head">
          <h2 id="disputes-heading" class="section-title">Disputes</h2>
          <span class="badge">{{ disputesList().length }} disputes</span>
        </div>
        <p class="muted small dispute-explainer">
          Respond to challenge the dispute by sending evidence to the issuer, or accept to forfeit the funds.
        </p>

        @if (disputesLoader.loading() && disputesList().length === 0) {
          <p class="muted small">Loading disputes…</p>
        } @else if (disputesLoader.error(); as err) {
          <div class="card-error" role="alert">
            <p class="error-msg">{{ err }}</p>
            <button type="button" class="btn btn-secondary btn-sm" (click)="disputesLoader.reload()">
              Retry
            </button>
          </div>
        } @else if (disputesList().length === 0) {
          <p class="muted small">
            No disputes on record. Cardholders can open a dispute from their bank if an issue arises.
          </p>
        } @else {
          <div class="disputes-list">
            @for (dispute of disputesList(); track dispute.id) {
              <article class="dispute-item card-subtle">
                <div class="dispute-top">
                  <div class="dispute-meta-col">
                    <span class="dispute-ref mono small">Payment: {{ dispute.payment }}</span>
                    <span class="dispute-reason">{{ formatReason(dispute) }}</span>
                    <span class="mono faint small">{{ dispute.createdAt | date: 'medium' }}</span>
                  </div>

                  <div class="dispute-status-col">
                    <span class="dispute-amount mono num">{{ formatMoney(dispute.amount) }}</span>
                    <span
                      class="badge"
                      [class.badge-approved]="dispute.status === 'won'"
                      [class.badge-declined]="dispute.status === 'lost'"
                      [class.badge-unknown]="dispute.status === 'needs_response' || dispute.status === 'under_review'"
                    >
                      {{ formatStatus(dispute.status) }}
                    </span>
                  </div>
                </div>

                @if (dispute.status === 'needs_response') {
                  <div class="dispute-actions-bar">
                    @if (activeActionDisputeId() !== dispute.id) {
                      <div class="dispute-btn-group">
                        <button
                          type="button"
                          class="btn btn-sm"
                          (click)="startResponding(dispute)"
                        >
                          Respond
                        </button>
                        <button
                          type="button"
                          class="btn btn-secondary btn-sm"
                          (click)="startAccepting(dispute)"
                        >
                          Accept
                        </button>
                      </div>
                    }

                    <!-- Respond inline form -->
                    @if (activeActionDisputeId() === dispute.id && actionMode() === 'respond') {
                      <div class="action-box">
                        <label [for]="'evidence-' + dispute.id">
                          Evidence for issuing bank (proof of delivery, invoice or customer messages)
                        </label>
                        <textarea
                          [id]="'evidence-' + dispute.id"
                          [(ngModel)]="evidenceText"
                          rows="3"
                          maxlength="2000"
                          placeholder="Explain why the charge is valid..."
                        ></textarea>
                        <div class="action-footer">
                          <span class="faint small">{{ evidenceText.length }} / 2000 characters</span>
                          <div class="action-btns">
                            <button
                              type="button"
                              class="btn btn-sm"
                              [disabled]="actionSubmitting() || evidenceText.trim().length === 0"
                              (click)="submitEvidence(dispute)"
                            >
                              {{ actionSubmitting() ? 'Sending…' : 'Send evidence' }}
                            </button>
                            <button
                              type="button"
                              class="btn btn-secondary btn-sm"
                              [disabled]="actionSubmitting()"
                              (click)="cancelAction()"
                            >
                              Cancel
                            </button>
                          </div>
                        </div>
                        @if (actionError()) {
                          <p class="error-msg small">{{ actionError() }}</p>
                        }
                      </div>
                    }

                    <!-- Accept confirm step -->
                    @if (activeActionDisputeId() === dispute.id && actionMode() === 'accept') {
                      <div class="action-box accept-confirm-box">
                        <p class="small">
                          Accept chargeback? The cardholder will keep the refund. This cannot be undone.
                        </p>
                        <div class="action-btns">
                          <button
                            type="button"
                            class="btn btn-sm"
                            [disabled]="actionSubmitting()"
                            (click)="confirmAccept(dispute)"
                          >
                            {{ actionSubmitting() ? 'Accepting…' : 'Confirm accept' }}
                          </button>
                          <button
                            type="button"
                            class="btn btn-secondary btn-sm"
                            [disabled]="actionSubmitting()"
                            (click)="cancelAction()"
                          >
                            Cancel
                          </button>
                        </div>
                        @if (actionError()) {
                          <p class="error-msg small">{{ actionError() }}</p>
                        }
                      </div>
                    }
                  </div>
                }
              </article>
            }
          </div>
        }
      </section>

      <!-- Recent Payments -->
      <app-orders-list
        title="Recent payments"
        subtitle="Recent purchases and refunds processed by the demo shop."
      />
    </main>
  `,
  styles: `
    .dashboard-header { display: flex; justify-content: space-between; align-items: flex-start; margin: 16px 0 24px; gap: 16px; }
    .summary-section { margin-bottom: 24px; }
    .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr)); gap: 16px; }
    .stat-card { display: flex; flex-direction: column; gap: 4px; }
    .stat-card-accent { border-color: var(--accent); background-color: var(--accent-soft); }
    .stat-label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--ink-faint); }
    .stat-value { font-size: 1.5rem; font-weight: 600; color: var(--ink); line-height: 1.2; }
    .stat-sub { margin-top: 2px; }
    .payouts-card, .disputes-card { margin-bottom: 24px; }
    .section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
    .section-title { font-size: 1.05rem; margin-bottom: 0; }
    .payout-explainer, .dispute-explainer { margin-bottom: 16px; }
    .font-semibold { font-weight: 600; }
    .disputes-list { display: flex; flex-direction: column; gap: 12px; }
    .dispute-item { padding: 14px; border-radius: var(--radius-sm); border: 1px solid var(--line); display: flex; flex-direction: column; gap: 12px; }
    .dispute-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
    .dispute-meta-col { display: flex; flex-direction: column; gap: 2px; }
    .dispute-ref { color: var(--ink-muted); }
    .dispute-reason { font-weight: 600; color: var(--ink); font-size: 0.9375rem; }
    .dispute-status-col { display: flex; align-items: center; gap: 8px; }
    .dispute-amount { font-weight: 600; }
    .dispute-actions-bar { padding-top: 8px; border-top: 1px solid var(--line); }
    .dispute-btn-group { display: flex; gap: 8px; }
    .action-box { display: flex; flex-direction: column; gap: 8px; padding: 10px; background-color: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); }
    .action-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .action-btns { display: flex; gap: 8px; align-items: center; }
    .accept-confirm-box { border-color: var(--line-strong); }
    .card-error { padding: 12px; background-color: var(--status-declined-bg); border: 1px solid var(--status-declined); border-radius: var(--radius-sm); color: var(--status-declined); margin-bottom: 12px; }
    .error-msg { font-weight: 500; margin: 0 0 6px; }
  `,
})
export class Dashboard implements OnInit {
  private readonly api = inject(Api);

  protected readonly payoutsLoader = new Loader<PayoutListResponse>(() => this.api.getPayouts());
  protected readonly disputesLoader = new Loader<DisputeListResponse>(() => this.api.getDisputes());

  protected readonly payoutsList = computed<Payout[]>(() => {
    return this.payoutsLoader.value()?.data ?? [];
  });

  protected readonly disputesList = computed<Dispute[]>(() => {
    return this.disputesLoader.value()?.data ?? [];
  });

  protected readonly summary = computed(() => {
    return computePayoutSummary(this.payoutsList());
  });

  // Dispute actions state
  protected readonly activeActionDisputeId = signal<string | null>(null);
  protected readonly actionMode = signal<'respond' | 'accept' | null>(null);
  protected readonly actionSubmitting = signal<boolean>(false);
  protected readonly actionError = signal<string>('');
  protected evidenceText = '';

  ngOnInit(): void {
    this.reloadAll();
  }

  protected reloadAll(): void {
    void this.payoutsLoader.reload();
    void this.disputesLoader.reload();
  }

  protected loadingAny(): boolean {
    return this.payoutsLoader.loading() || this.disputesLoader.loading();
  }

  protected formatMoney(cents: number): string {
    return formatCents(cents);
  }

  protected formatReason(dispute: Dispute): string {
    return disputeReasonLabel(dispute.reasonCode || dispute.reason);
  }

  protected formatStatus(status: string): string {
    return disputeStatusLabel(status);
  }

  protected startResponding(dispute: Dispute): void {
    this.activeActionDisputeId.set(dispute.id);
    this.actionMode.set('respond');
    this.actionError.set('');

    const formattedDate = new Date(dispute.createdAt).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
    this.evidenceText = `Delivered on ${formattedDate}, signed for by the cardholder.`;
  }

  protected startAccepting(dispute: Dispute): void {
    this.activeActionDisputeId.set(dispute.id);
    this.actionMode.set('accept');
    this.actionError.set('');
  }

  protected cancelAction(): void {
    this.activeActionDisputeId.set(null);
    this.actionMode.set(null);
    this.actionError.set('');
    this.evidenceText = '';
  }

  protected async submitEvidence(dispute: Dispute): Promise<void> {
    const text = this.evidenceText.trim();
    if (!text) {
      return;
    }

    this.actionSubmitting.set(true);
    this.actionError.set('');

    try {
      await this.api.submitDisputeEvidence(dispute.id, { evidence: text });
      this.cancelAction();
      void this.disputesLoader.reload();
    } catch (err: unknown) {
      this.actionError.set(messageOf(err));
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  protected async confirmAccept(dispute: Dispute): Promise<void> {
    this.actionSubmitting.set(true);
    this.actionError.set('');

    try {
      await this.api.acceptDispute(dispute.id);
      this.cancelAction();
      void this.disputesLoader.reload();
    } catch (err: unknown) {
      this.actionError.set(messageOf(err));
    } finally {
      this.actionSubmitting.set(false);
    }
  }
}
