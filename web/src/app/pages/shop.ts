import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Api, createOrderId } from '../core/api';
import { groupCardNumber } from '../core/cards';
import { Loader } from '../core/loader';
import { formatCents } from '../core/money';
import { isRetryable, messageOf } from '../core/problem';
import { Payment, Product, ShopConfig, TestCard } from '../core/types';
import { CopyButton } from '../ui/copy-button';

interface HostedFormReadyMessage {
  type: 'cardrail:ready';
}

interface HostedFormTokenMessage {
  type: 'cardrail:token';
  token: {
    id: string;
    last4: string;
    brand: string;
  };
}

interface HostedFormErrorMessage {
  type: 'cardrail:error';
  message: string;
}

type HostedFormMessage =
  | HostedFormReadyMessage
  | HostedFormTokenMessage
  | HostedFormErrorMessage;

@Component({
  selector: 'app-shop',
  imports: [RouterLink, CopyButton, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="shop-header">
        <div>
          <h1>Railpay demo shop</h1>
          <p class="muted">
            Select items and checkout using test payment cards to see real-time network routing.
          </p>
        </div>
      </div>

      @if (configLoader.loading()) {
        <div class="card loading-card" role="status">
          <p class="muted">Loading shop catalogue…</p>
        </div>
      } @else if (configLoader.error(); as err) {
        <div class="card error-card" role="alert">
          <p class="error-msg">{{ err }}</p>
          <button type="button" class="btn btn-secondary btn-sm" (click)="configLoader.reload()">
            Retry loading shop
          </button>
        </div>
      } @else if (configLoader.value(); as cfg) {
        <div class="shop-layout">
          <!-- Left Column: Checkout Section -->
          <div class="checkout-column">
            <!-- Product selection card -->
            <section class="card" aria-labelledby="heading-items">
              <h2 id="heading-items" class="card-title">1. Select products</h2>

              <div class="field">
                <label for="product-select">Product</label>
                <select
                  id="product-select"
                  [value]="selectedProductId()"
                  (change)="onProductChange($event)"
                >
                  @for (prod of cfg.products; track prod.id) {
                    <option [value]="prod.id">
                      {{ prod.name }} &mdash; {{ formatMoney(prod.priceCents) }}
                    </option>
                  }
                </select>
              </div>

              <div class="field">
                <label for="quantity-select">Quantity (1 to 5)</label>
                <select
                  id="quantity-select"
                  [value]="quantity()"
                  (change)="onQuantityChange($event)"
                >
                  <option [value]="1">1</option>
                  <option [value]="2">2</option>
                  <option [value]="3">3</option>
                  <option [value]="4">4</option>
                  <option [value]="5">5</option>
                </select>
              </div>

              <div class="order-summary">
                <span class="muted">Total due</span>
                <span class="total-amount mono num">{{ formatMoney(totalCents()) }}</span>
              </div>
            </section>

            <!-- Card payment card -->
            <section class="card checkout-card" aria-labelledby="heading-payment">
              <div class="payment-title-row">
                <h2 id="heading-payment" class="card-title">2. Card payment</h2>
                <span class="badge">Hosted by Railpay</span>
              </div>

              <div class="security-notice">
                <svg class="notice-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                  <rect x="3" y="7" width="10" height="7" rx="1.5" />
                  <path d="M5 7V4.5a3 3 0 0 1 6 0V7" />
                </svg>
                <span>
                  Card details go directly to Railpay and never touch the shop's servers.
                </span>
              </div>

              <!-- Hosted Iframe Container -->
              <div class="iframe-box">
                @if (iframeUrl()) {
                  <iframe
                    #cardIframe
                    [src]="iframeUrl()"
                    title="Railpay hosted card input form"
                    class="card-iframe"
                    [class.loaded]="iframeReady()"
                    allow="payment"
                  ></iframe>
                }
                @if (!iframeReady()) {
                  <div class="iframe-placeholder" role="status">
                    <p class="muted small">Loading secure card form…</p>
                  </div>
                }
              </div>

              <!-- Action buttons & status -->
              <div class="checkout-actions">
                <button
                  type="button"
                  class="btn btn-pay"
                  [disabled]="!canPay()"
                  (click)="pay()"
                >
                  @if (paying()) {
                    Processing payment…
                  } @else {
                    Pay {{ formatMoney(totalCents()) }}
                  }
                </button>
              </div>

              <!-- Payment Errors -->
              @if (checkoutError()) {
                <div class="card-error-notice" role="alert" aria-live="assertive">
                  <p class="error-msg">{{ checkoutError() }}</p>
                  @if (canRetryCurrentOrder()) {
                    <p class="small muted">
                      You can safely retry payment; your order ID is preserved.
                    </p>
                  }
                </div>
              }

              <!-- Definite Outcome Alert -->
              @if (paymentOutcome(); as p) {
                <div
                  class="outcome-card"
                  [class.outcome-approved]="p.status === 'authorised'"
                  [class.outcome-declined]="p.status === 'declined'"
                  [class.outcome-unknown]="p.status === 'unknown'"
                  role="status"
                  aria-live="polite"
                >
                  <div class="outcome-header">
                    <span class="outcome-badge">
                      {{ p.status === 'authorised' ? 'Payment Approved' : p.status === 'declined' ? 'Payment Declined' : 'Payment Status Unknown' }}
                    </span>
                    <span class="outcome-time mono small">{{ p.createdAt | date: 'mediumTime' }}</span>
                  </div>

                  <div class="outcome-content">
                    @if (p.status === 'authorised') {
                      <p>
                        Authorisation code:
                        <strong class="mono">{{ p.authCode || 'Approved' }}</strong>
                      </p>
                    } @else if (p.status === 'declined') {
                      <p>
                        Reason:
                        <strong>{{ p.declineReason || 'Transaction declined by card issuer' }}</strong>
                      </p>
                    } @else {
                      <p>
                        The network did not return a definitive result. Check your trace or bank statement.
                      </p>
                    }

                    <div class="outcome-trace-cta">
                      <a [routerLink]="['/trace', p.reference]" class="btn btn-trace">
                        Watch this payment's journey &rarr;
                      </a>
                    </div>
                  </div>
                </div>
              }
            </section>
          </div>

          <!-- Right Column: Try a Test Card Helper -->
          <div class="helper-column">
            <aside class="card helper-card" aria-labelledby="heading-test-cards">
              <div class="helper-header">
                <h2 id="heading-test-cards" class="card-title">Try a test card</h2>
                <a routerLink="/test-cards" class="small">All cards &rarr;</a>
              </div>
              <p class="muted small">
                Copy and paste test numbers directly into the hosted form to simulate different issuer outcomes:
              </p>

              @if (cardsLoader.loading()) {
                <p class="muted small">Loading test cards…</p>
              } @else if (cardsLoader.value(); as cards) {
                <div class="cards-list">
                  @for (card of cards; track card.number) {
                    <div class="test-card-item">
                      <div class="test-card-head">
                        <span class="card-kind-badge">{{ card.kind }}</span>
                        <span class="card-outcome-desc">{{ card.outcome }}</span>
                      </div>

                      <div class="card-digits-row">
                        <span class="card-digits mono">{{ formatCard(card.number) }}</span>
                        <app-copy-button
                          [textToCopy]="card.number"
                          fieldName="card number"
                        />
                      </div>

                      <div class="card-sub-digits">
                        <div class="sub-digit-item">
                          <span class="faint small">Exp:</span>
                          <span class="mono">{{ card.expiry }}</span>
                          <app-copy-button
                            [textToCopy]="card.expiry"
                            fieldName="expiry"
                          />
                        </div>

                        <div class="sub-digit-item">
                          <span class="faint small">CVV:</span>
                          <span class="mono">{{ card.cvv2 }}</span>
                          <app-copy-button
                            [textToCopy]="card.cvv2"
                            fieldName="security code"
                          />
                        </div>
                      </div>
                    </div>
                  }
                </div>
              }
            </aside>
          </div>
        </div>
      }
    </main>
  `,
  styles: `
    .shop-header {
      margin: 16px 0 24px;
    }

    .shop-layout {
      display: grid;
      grid-template-columns: 1fr;
      gap: 24px;
    }

    @media (min-width: 900px) {
      .shop-layout {
        grid-template-columns: 1.15fr 0.85fr;
      }
    }

    .checkout-column {
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .helper-column {
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .card-title {
      font-size: 1.05rem;
      margin-bottom: 14px;
    }

    .payment-title-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
    }

    .order-summary {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-top: 16px;
      margin-top: 16px;
      border-top: 1px solid var(--line);
    }

    .total-amount {
      font-size: 1.25rem;
      font-weight: 600;
      color: var(--ink);
    }

    .security-notice {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 12px;
      background-color: var(--surface-subtle);
      border-radius: var(--radius-sm);
      font-size: 0.8125rem;
      color: var(--ink-muted);
      margin-bottom: 16px;
    }

    .notice-icon {
      width: 14px;
      height: 14px;
      flex-shrink: 0;
      color: var(--accent);
    }

    .iframe-box {
      position: relative;
      min-height: 248px;
      border: 1px solid var(--line);
      border-radius: var(--radius-sm);
      background-color: var(--surface-subtle);
      overflow: hidden;
    }

    .card-iframe {
      width: 100%;
      height: 248px;
      border: none;
      display: block;
      opacity: 0;
      transition: opacity var(--duration-fast) var(--ease-out);
    }

    .card-iframe.loaded {
      opacity: 1;
    }

    .iframe-placeholder {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background-color: var(--surface-subtle);
    }

    .checkout-actions {
      margin-top: 16px;
    }

    .btn-pay {
      width: 100%;
    }

    .card-error-notice {
      margin-top: 14px;
      padding: 12px;
      background-color: var(--status-declined-bg);
      border: 1px solid var(--status-declined);
      border-radius: var(--radius-sm);
      color: var(--status-declined);
    }

    .error-msg {
      font-weight: 500;
      margin-bottom: 4px;
    }

    .outcome-card {
      margin-top: 20px;
      padding: 16px;
      border-radius: var(--radius);
      border: 1px solid var(--line);
    }

    .outcome-approved {
      background-color: var(--status-approved-bg);
      border-color: var(--status-approved);
      color: var(--status-approved);
    }

    .outcome-declined {
      background-color: var(--status-declined-bg);
      border-color: var(--status-declined);
      color: var(--status-declined);
    }

    .outcome-unknown {
      background-color: var(--status-unknown-bg);
      border-color: var(--status-unknown);
      color: var(--status-unknown);
    }

    .outcome-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 10px;
    }

    .outcome-badge {
      font-size: 0.8125rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .outcome-content p {
      color: var(--ink);
      margin-bottom: 14px;
    }

    .outcome-trace-cta {
      margin-top: 12px;
    }

    .btn-trace {
      background-color: var(--ink);
      color: var(--bg);
      font-weight: 600;
      width: 100%;
    }

    .btn-trace:hover {
      background-color: var(--ink-muted);
      color: var(--bg);
    }

    /* Helper Column Styles */
    .helper-header {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      margin-bottom: 8px;
    }

    .cards-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
      margin-top: 14px;
    }

    .test-card-item {
      padding: 12px;
      background-color: var(--surface-subtle);
      border: 1px solid var(--line);
      border-radius: var(--radius-sm);
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .test-card-head {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .card-kind-badge {
      font-size: 0.6875rem;
      font-weight: 600;
      padding: 2px 6px;
      background-color: var(--line);
      border-radius: var(--radius-sm);
      color: var(--ink);
    }

    .card-outcome-desc {
      font-size: 0.75rem;
      color: var(--ink-muted);
      font-weight: 500;
    }

    .card-digits-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }

    .card-digits {
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--ink);
      letter-spacing: 0.04em;
    }

    .card-sub-digits {
      display: flex;
      align-items: center;
      gap: 16px;
      font-size: 0.8125rem;
    }

    .sub-digit-item {
      display: flex;
      align-items: center;
      gap: 6px;
    }
  `,
})
export class Shop implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  private readonly sanitizer = inject(DomSanitizer);

  @ViewChild('cardIframe') private iframeRef?: ElementRef<HTMLIFrameElement>;

  protected readonly configLoader = new Loader<ShopConfig>(() => this.api.getShopConfig());
  protected readonly cardsLoader = new Loader<TestCard[]>(() => this.api.getTestCards());

  protected readonly selectedProductId = signal<string>('');
  protected readonly quantity = signal<number>(1);
  protected readonly iframeReady = signal<boolean>(false);
  protected readonly iframeVersion = signal<number>(1);
  protected readonly paying = signal<boolean>(false);
  protected readonly checkoutError = signal<string>('');
  protected readonly paymentOutcome = signal<Payment | null>(null);
  protected readonly canRetryCurrentOrder = signal<boolean>(false);

  private currentOrderId = createOrderId();
  private pendingToken: string | null = null;
  private tokenPromiseResolve: ((token: { id: string }) => void) | null = null;
  private tokenPromiseReject: ((err: Error) => void) | null = null;

  protected readonly iframeUrl = computed<SafeResourceUrl | null>(() => {
    const cfg = this.configLoader.value();
    // Re-evaluates when iframeVersion increments
    this.iframeVersion();
    if (!cfg || !cfg.publishableKey) {
      return null;
    }
    const origin = typeof window !== 'undefined' ? window.location.origin : '';
    const src = `/hosted/card-form?key=${encodeURIComponent(cfg.publishableKey)}&origin=${encodeURIComponent(origin)}`;
    return this.sanitizer.bypassSecurityTrustResourceUrl(src);
  });

  protected readonly selectedProduct = computed<Product | null>(() => {
    const cfg = this.configLoader.value();
    if (!cfg || !cfg.products || cfg.products.length === 0) {
      return null;
    }
    const currentId = this.selectedProductId();
    return cfg.products.find((p) => p.id === currentId) ?? cfg.products[0];
  });

  protected readonly totalCents = computed<number>(() => {
    const p = this.selectedProduct();
    if (!p) {
      return 0;
    }
    return p.priceCents * this.quantity();
  });

  protected readonly canPay = computed<boolean>(() => {
    return (
      !this.paying() &&
      this.iframeReady() &&
      this.selectedProduct() !== null &&
      this.quantity() >= 1 &&
      this.quantity() <= 5
    );
  });

  ngOnInit(): void {
    // Select the first product once config loads
    void this.configLoader.reload().then(() => {
      const cfg = this.configLoader.value();
      if (cfg && cfg.products.length > 0) {
        this.selectedProductId.set(cfg.products[0].id);
      }
    });
  }

  ngOnDestroy(): void {
    this.cancelPendingTokenization();
  }

  @HostListener('window:message', ['$event'])
  onWindowMessage(event: MessageEvent): void {
    if (typeof window === 'undefined') {
      return;
    }
    // Only accept messages whose event.origin is location.origin and event.source is the iframe
    if (event.origin !== window.location.origin) {
      return;
    }
    if (this.iframeRef && event.source !== this.iframeRef.nativeElement.contentWindow) {
      return;
    }

    const data = event.data as HostedFormMessage;
    if (!data || typeof data.type !== 'string') {
      return;
    }

    if (data.type === 'cardrail:ready') {
      this.iframeReady.set(true);
    } else if (data.type === 'cardrail:token') {
      if (this.tokenPromiseResolve) {
        this.tokenPromiseResolve(data.token);
        this.tokenPromiseResolve = null;
        this.tokenPromiseReject = null;
      }
    } else if (data.type === 'cardrail:error') {
      if (this.tokenPromiseReject) {
        this.tokenPromiseReject(new Error(data.message || 'Tokenisation failed'));
        this.tokenPromiseResolve = null;
        this.tokenPromiseReject = null;
      }
    }
  }

  protected onProductChange(event: Event): void {
    const target = event.target as HTMLSelectElement;
    this.selectedProductId.set(target.value);
  }

  protected onQuantityChange(event: Event): void {
    const target = event.target as HTMLSelectElement;
    const q = Number.parseInt(target.value, 10);
    this.quantity.set(Math.max(1, Math.min(5, Number.isNaN(q) ? 1 : q)));
  }

  protected async pay(): Promise<void> {
    const product = this.selectedProduct();
    if (!product || !this.canPay()) {
      return;
    }

    this.paying.set(true);
    this.checkoutError.set('');
    this.canRetryCurrentOrder.set(false);

    // 1. The card goes to Railpay's form, which hands back a token. A retry of the same order reuses its token:
    //    the order id is the idempotency key, and a repeat must be the same request to get the first answer.
    let token = this.pendingToken;
    if (!token) {
      try {
        token = (await this.requestTokenFromIframe()).id;
      } catch (err: unknown) {
        // Nothing was sent to the shop. Keep the form as it is, so a typo can be fixed in place.
        this.checkoutError.set(messageOf(err));
        this.paying.set(false);
        return;
      }
      this.pendingToken = token;
    }

    // 2. The shop's server takes the payment with that token.
    try {
      const payment = await this.api.checkout({
        product: product.id,
        quantity: this.quantity(),
        token,
        orderId: this.currentOrderId,
      });
      this.paymentOutcome.set(payment);
      this.startNewOrder();
    } catch (err: unknown) {
      this.checkoutError.set(messageOf(err));
      if (isRetryable(err)) {
        // We don't know if it went through. Paying again sends the same order, which is never charged twice.
        this.canRetryCurrentOrder.set(true);
      } else {
        this.startNewOrder();
      }
    } finally {
      this.paying.set(false);
    }
  }

  /** A token works once: after a definite answer, the next payment is a new order with a fresh form. */
  private startNewOrder(): void {
    this.currentOrderId = createOrderId();
    this.pendingToken = null;
    this.reloadIframe();
  }

  private requestTokenFromIframe(): Promise<{ id: string }> {
    return new Promise((resolve, reject) => {
      if (!this.iframeRef?.nativeElement.contentWindow) {
        reject(new Error('Hosted card form is not active.'));
        return;
      }

      const timeout = setTimeout(() => {
        if (this.tokenPromiseReject) {
          this.tokenPromiseReject(new Error('Card form tokenisation timed out. Please try again.'));
          this.tokenPromiseResolve = null;
          this.tokenPromiseReject = null;
        }
      }, 15000);
      this.tokenPromiseResolve = (token) => {
        clearTimeout(timeout);
        resolve(token);
      };
      this.tokenPromiseReject = (err) => {
        clearTimeout(timeout);
        reject(err);
      };

      // Post tokenise message to iframe
      const targetOrigin = window.location.origin;
      this.iframeRef.nativeElement.contentWindow.postMessage(
        { type: 'cardrail:tokenise' },
        targetOrigin,
      );
    });
  }

  private cancelPendingTokenization(): void {
    if (this.tokenPromiseReject) {
      this.tokenPromiseReject(new Error('Payment cancelled.'));
      this.tokenPromiseResolve = null;
      this.tokenPromiseReject = null;
    }
  }

  private reloadIframe(): void {
    this.iframeReady.set(false);
    this.iframeVersion.update((v) => v + 1);
  }

  protected formatMoney(cents: number): string {
    return formatCents(cents);
  }

  protected formatCard(num: string): string {
    return groupCardNumber(num);
  }
}
