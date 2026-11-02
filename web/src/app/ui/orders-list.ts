import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { DISPUTE_REASONS, disputeErrorMessage } from '../core/disputes';
import { Loader } from '../core/loader';
import { formatCents } from '../core/money';
import { isRetryable, messageOf } from '../core/problem';
import {
  canRefundPayment,
  createRefundRequestId,
  parseRefundAmount,
  remainingRefundableCents,
} from '../core/refund';
import {
  IssuerDisputeResponse,
  Payment,
  PaymentListResponse,
} from '../core/types';

@Component({
  selector: 'app-orders-list',
  imports: [RouterLink, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card orders-card" aria-labelledby="orders-list-heading">
      <div class="orders-header">
        <div>
          <h2 id="orders-list-heading" class="card-title">{{ title() }}</h2>
          <p class="muted small">{{ subtitle() }}</p>
        </div>
        <button
          type="button"
          class="btn btn-secondary btn-sm"
          [disabled]="paymentsLoader.loading()"
          (click)="reload()"
        >
          {{ paymentsLoader.loading() ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>

      @if (paymentsLoader.loading() && !paymentsLoader.value()) {
        <p class="muted small">Loading orders…</p>
      } @else if (paymentsLoader.error(); as err) {
        <div class="card-error-notice" role="alert">
          <p class="error-msg">{{ err }}</p>
          <button type="button" class="btn btn-secondary btn-sm" (click)="reload()">
            Retry
          </button>
        </div>
      } @else if (paymentsLoader.value(); as resp) {
        @if (!resp.data || resp.data.length === 0) {
          <p class="muted small">No orders recorded yet.</p>
        } @else {
          <div class="orders-list">
            @for (order of resp.data; track order.id) {
              <article class="order-item">
                <div class="order-main-row">
                  <div class="order-desc-col">
                    <span class="order-desc">{{ order.description || 'Order' }}</span>
                    <span class="order-card-meta mono small faint">
                      {{ order.card.brand }} &bull;&bull;&bull;&bull; {{ order.card.last4 }} &middot; {{ order.createdAt | date: 'shortTime' }}
                    </span>
                  </div>

                  <div class="order-amount-col">
                    <span class="order-amount mono num">{{ formatMoney(order.amount) }}</span>
                    <span
                      class="badge"
                      [class.badge-approved]="order.status === 'captured' || order.status === 'authorised'"
                      [class.badge-declined]="order.status === 'declined' || order.status === 'failed'"
                      [class.badge-unknown]="order.status === 'reversed' || order.status === 'voided' || order.status === 'unknown'"
                    >
                      {{ order.status }}
                    </span>
                  </div>
                </div>

                <div class="order-actions-row">
                  <div class="actions-left">
                    <a [routerLink]="['/trace', order.reference]" class="small">View trace &rarr;</a>
                    @if (order.status === 'captured') {
                      <button
                        type="button"
                        class="dispute-link small"
                        (click)="openDispute(order)"
                      >
                        Dispute as the cardholder
                      </button>
                    }
                  </div>

                  <div class="actions-right">
                    @if (canRefund(order)) {
                      <button
                        type="button"
                        class="btn btn-secondary btn-sm"
                        (click)="openRefund(order)"
                      >
                        Refund
                      </button>
                    }
                  </div>
                </div>

                <!-- Inline dispute form -->
                @if (activeDisputePaymentId() === order.id) {
                  <div class="dispute-inline-form card-subtle" role="region" aria-label="Dispute payment form">
                    <div class="form-header-row">
                      <label [for]="'dispute-reason-' + order.id">
                        Dispute reason (cardholder claim)
                      </label>
                      <span class="muted small">Issuer dispute</span>
                    </div>

                    <div class="dispute-input-row">
                      <select
                        [id]="'dispute-reason-' + order.id"
                        [value]="selectedDisputeReason()"
                        (change)="onDisputeReasonChange($event)"
                        class="dispute-select"
                      >
                        @for (reason of disputeReasons; track reason.code) {
                          <option [value]="reason.code">
                            {{ reason.label }} ({{ reason.code }})
                          </option>
                        }
                      </select>
                      <button
                        type="button"
                        class="btn btn-sm"
                        [disabled]="disputing()"
                        (click)="submitDispute(order)"
                      >
                        {{ disputing() ? 'Submitting…' : 'Submit dispute' }}
                      </button>
                      <button
                        type="button"
                        class="btn btn-secondary btn-sm"
                        [disabled]="disputing()"
                        (click)="closeDispute()"
                      >
                        Cancel
                      </button>
                    </div>

                    @if (disputeError()) {
                      <p class="error-msg small">{{ disputeError() }}</p>
                    }

                    @if (disputeResult(); as result) {
                      <div class="dispute-result-banner" role="status">
                        <p class="small">
                          Dispute opened (ID: <strong class="mono">{{ result.id }}</strong>).
                          Amount: <strong class="mono num">{{ formatMoney(result.amountCents) }}</strong>.
                        </p>
                        <a routerLink="/dashboard" class="small">View in shop dashboard &rarr;</a>
                      </div>
                    }
                  </div>
                }

                <!-- Inline refund form -->
                @if (activeRefundPaymentId() === order.id) {
                  <div class="refund-inline-form card-subtle">
                    <div class="form-header-row">
                      <label [for]="'refund-input-' + order.id">
                        Refund amount (up to {{ formatMoney(remainingCents(order)) }})
                      </label>
                      <span class="muted small">Default: all that is left</span>
                    </div>

                    <div class="refund-input-row">
                      <input
                        [id]="'refund-input-' + order.id"
                        type="text"
                        [value]="refundInputVal()"
                        (input)="onRefundInputChange($event)"
                        placeholder="Leave blank for all left"
                        class="refund-input mono"
                      />
                      <button
                        type="button"
                        class="btn btn-sm"
                        [disabled]="refunding() || !isRefundInputValid(order)"
                        (click)="submitRefund(order)"
                      >
                        {{ refunding() ? 'Submitting…' : 'Confirm refund' }}
                      </button>
                      <button
                        type="button"
                        class="btn btn-secondary btn-sm"
                        [disabled]="refunding()"
                        (click)="closeRefund()"
                      >
                        Cancel
                      </button>
                    </div>

                    @if (refundError()) {
                      <p class="error-msg small">{{ refundError() }}</p>
                    }
                    @if (refundRetryable()) {
                      <p class="small muted">
                        Network issue. You can safely retry; your request ID is preserved.
                      </p>
                    }
                  </div>
                }

                <!-- Refunds list under payment -->
                @if (order.refunds && order.refunds.length > 0) {
                  <div class="refunds-sublist">
                    <span class="faint small">Refunds:</span>
                    @for (ref of order.refunds; track ref.id) {
                      <div class="refund-subitem">
                        <span class="mono num">{{ formatMoney(ref.amount) }}</span>
                        <span
                          class="badge"
                          [class.badge-approved]="ref.status === 'succeeded'"
                          [class.badge-declined]="ref.status === 'failed'"
                        >
                          {{ ref.status }}
                        </span>
                        <span class="mono small faint">{{ ref.createdAt | date: 'shortTime' }}</span>
                        <a [routerLink]="['/trace', ref.reference]" class="small">Trace &rarr;</a>
                        @if (ref.status === 'failed' && ref.failureReason) {
                          <span class="small error-text">({{ ref.failureReason }})</span>
                        }
                      </div>
                    }
                  </div>
                }
              </article>
            }
          </div>
        }
      }
    </section>
  `,
  styles: `
    .orders-card { display: flex; flex-direction: column; }
    .card-title { font-size: 1.05rem; margin-bottom: 4px; }
    .orders-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; gap: 12px; }
    .orders-list { display: flex; flex-direction: column; gap: 12px; }
    .order-item { padding: 12px; background-color: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius-sm); display: flex; flex-direction: column; gap: 8px; }
    .order-main-row { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
    .order-desc-col { display: flex; flex-direction: column; gap: 2px; }
    .order-desc { font-weight: 600; color: var(--ink); font-size: 0.9375rem; }
    .order-amount-col { display: flex; align-items: center; gap: 8px; }
    .order-amount { font-weight: 600; }
    .order-actions-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-top: 4px; flex-wrap: wrap; }
    .actions-left { display: flex; align-items: center; gap: 14px; }
    .actions-right { display: flex; align-items: center; gap: 8px; }
    .dispute-link { background: none; border: none; padding: 0; color: var(--ink-faint); text-decoration: underline; text-underline-offset: 3px; cursor: pointer; font-family: inherit; transition: color var(--duration-fast) var(--ease-out); }
    .dispute-link:hover { color: var(--accent); }
    .dispute-inline-form, .refund-inline-form { margin-top: 8px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
    .form-header-row { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
    .form-header-row label { margin-bottom: 0; }
    .dispute-input-row, .refund-input-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .dispute-select { min-width: 220px; flex: 1 1 auto; }
    .refund-input { max-width: 140px; }
    .dispute-result-banner { padding: 8px 10px; background-color: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
    .dispute-result-banner p { margin: 0; }
    .card-error-notice { padding: 12px; background-color: var(--status-declined-bg); border: 1px solid var(--status-declined); border-radius: var(--radius-sm); color: var(--status-declined); margin-bottom: 12px; }
    .error-msg { font-weight: 500; margin-bottom: 4px; }
    .error-text { color: var(--status-declined); }
    .refunds-sublist { margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--line); display: flex; flex-direction: column; gap: 4px; }
    .refund-subitem { display: flex; align-items: center; gap: 8px; font-size: 0.8125rem; }
  `,
})
export class OrdersList implements OnInit {
  private readonly api = inject(Api);

  readonly title = input<string>('Your orders');
  readonly subtitle = input<string>('Recent payments processed by the shop.');

  protected readonly paymentsLoader = new Loader<PaymentListResponse>(() => this.api.getPayments());

  // Dispute state
  protected readonly disputeReasons = DISPUTE_REASONS;
  protected readonly activeDisputePaymentId = signal<string | null>(null);
  protected readonly selectedDisputeReason = signal<string>('1310');
  protected readonly disputing = signal<boolean>(false);
  protected readonly disputeError = signal<string>('');
  protected readonly disputeResult = signal<IssuerDisputeResponse | null>(null);

  // Refund state
  protected readonly activeRefundPaymentId = signal<string | null>(null);
  protected readonly refundInputVal = signal<string>('');
  protected readonly refunding = signal<boolean>(false);
  protected readonly refundError = signal<string>('');
  protected readonly refundRetryable = signal<boolean>(false);
  private currentRefundRequestId = createRefundRequestId();

  ngOnInit(): void {
    void this.paymentsLoader.reload();
  }

  public reload(): Promise<void> {
    return this.paymentsLoader.reload();
  }

  // Dispute actions
  protected openDispute(order: Payment): void {
    this.activeDisputePaymentId.set(order.id);
    this.selectedDisputeReason.set('1310');
    this.disputeError.set('');
    this.disputeResult.set(null);
    this.closeRefund();
  }

  protected closeDispute(): void {
    this.activeDisputePaymentId.set(null);
    this.disputeError.set('');
    this.disputeResult.set(null);
  }

  protected onDisputeReasonChange(event: Event): void {
    const target = event.target as HTMLSelectElement;
    this.selectedDisputeReason.set(target.value);
  }

  protected async submitDispute(order: Payment): Promise<void> {
    this.disputing.set(true);
    this.disputeError.set('');
    this.disputeResult.set(null);

    try {
      const res = await this.api.createIssuerDispute({
        reference: order.reference,
        reason: this.selectedDisputeReason(),
      });
      this.disputeResult.set(res);
      void this.paymentsLoader.reload();
    } catch (err: unknown) {
      this.disputeError.set(disputeErrorMessage(err));
    } finally {
      this.disputing.set(false);
    }
  }

  // Refund actions
  protected openRefund(payment: Payment): void {
    const leftCents = remainingRefundableCents(payment);
    this.activeRefundPaymentId.set(payment.id);
    this.refundInputVal.set((leftCents / 100).toFixed(2));
    this.refundError.set('');
    this.refundRetryable.set(false);
    this.currentRefundRequestId = createRefundRequestId();
    this.closeDispute();
  }

  protected closeRefund(): void {
    this.activeRefundPaymentId.set(null);
    this.refundInputVal.set('');
    this.refundError.set('');
    this.refundRetryable.set(false);
  }

  protected onRefundInputChange(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.refundInputVal.set(target.value);
    this.refundError.set('');
  }

  protected isRefundInputValid(payment: Payment): boolean {
    const maxCents = remainingRefundableCents(payment);
    const res = parseRefundAmount(this.refundInputVal(), maxCents);
    return res.valid;
  }

  protected remainingCents(payment: Payment): number {
    return remainingRefundableCents(payment);
  }

  protected canRefund(payment: Payment): boolean {
    return canRefundPayment(payment);
  }

  protected async submitRefund(payment: Payment): Promise<void> {
    const maxCents = remainingRefundableCents(payment);
    const parsed = parseRefundAmount(this.refundInputVal(), maxCents);
    if (!parsed.valid) {
      this.refundError.set(parsed.error ?? 'Invalid refund amount.');
      return;
    }

    this.refunding.set(true);
    this.refundError.set('');
    this.refundRetryable.set(false);

    try {
      await this.api.refundPayment(payment.id, {
        amount: parsed.cents,
        requestId: this.currentRefundRequestId,
      });
      this.closeRefund();
      await this.paymentsLoader.reload();
    } catch (err: unknown) {
      this.refundError.set(messageOf(err));
      if (isRetryable(err)) {
        this.refundRetryable.set(true);
      } else {
        this.currentRefundRequestId = createRefundRequestId();
        this.refundRetryable.set(false);
      }
    } finally {
      this.refunding.set(false);
    }
  }

  protected formatMoney(cents: number): string {
    return formatCents(cents);
  }
}
