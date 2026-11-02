import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  OnDestroy,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { CardholderApi } from '../core/cardholder-api';
import {
  BLOCKED_MCC_CATEGORIES,
  formatTransactionStatus,
  getMccLabel,
  isSharedCardProblem,
  parseDollarLimit,
} from '../core/cardholder';
import { groupCardNumber } from '../core/cards';
import { DISPUTE_REASONS } from '../core/disputes';
import { formatCents } from '../core/money';
import { messageOf } from '../core/problem';
import {
  CardholderAuthentication,
  CardholderCard,
  CardholderCardStatus,
  CardholderControls,
  CardholderTransaction,
  IssuedCardDetails,
  TestCard,
} from '../core/types';
import { CopyButton } from '../ui/copy-button';

@Component({
  selector: 'app-card',
  imports: [RouterLink, DatePipe, CopyButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="page-header">
        <div>
          <h1>Kestrel Bank</h1>
          <p class="muted">
            Cardholder app: view transactions, approve step-up payments, and set security rules.
          </p>
        </div>
        @if (api.isAuthenticated()) {
          <button
            type="button"
            class="btn btn-secondary btn-sm"
            (click)="signOut()"
          >
            Sign out
          </button>
        }
      </div>

      <!-- SHARED CARD 403 NOTICE -->
      @if (sharedCardNotice()) {
        <div class="card shared-card-alert" role="alert">
          <p class="shared-card-msg">
            This is a shared test card, so it can't be changed. Open a card of your own to try this.
          </p>
          <div class="shared-card-actions">
            <button
              type="button"
              class="btn btn-sm"
              [disabled]="openingCard()"
              (click)="openMyOwnCard('DEBIT')"
            >
              Open Debit card ($1,000)
            </button>
            <button
              type="button"
              class="btn btn-secondary btn-sm"
              [disabled]="openingCard()"
              (click)="openMyOwnCard('CREDIT')"
            >
              Open Credit card ($1,000 limit)
            </button>
            <button
              type="button"
              class="btn btn-quiet btn-sm"
              (click)="sharedCardNotice.set(false)"
            >
              Dismiss
            </button>
          </div>
        </div>
      }

      <!-- SIGNED-OUT STATE -->
      @if (!api.isAuthenticated()) {
        <div class="auth-layout">
          <section class="card auth-card" aria-labelledby="signin-heading">
            <h2 id="signin-heading" class="card-title">Sign in to your card</h2>
            <p class="muted small">
              Enter your card number, expiration date, and CVV to open your cardholder account.
            </p>

            <form (submit)="signIn($event)" class="signin-form">
              <div class="field">
                <label for="card-num">Card number</label>
                <input
                  id="card-num"
                  type="text"
                  class="mono"
                  [value]="inputNumber()"
                  (input)="onNumberInput($event)"
                  placeholder="2860 0100 0000 0012"
                  autocomplete="cc-number"
                  required
                />
              </div>

              <div class="form-row-2">
                <div class="field">
                  <label for="card-exp">Expiry (MM/YY)</label>
                  <input
                    id="card-exp"
                    type="text"
                    class="mono"
                    [value]="inputExpiry()"
                    (input)="inputExpiry.set($any($event.target).value)"
                    placeholder="12/28"
                    autocomplete="cc-exp"
                    maxlength="5"
                    required
                  />
                </div>
                <div class="field">
                  <label for="card-cvv">Security code</label>
                  <input
                    id="card-cvv"
                    type="password"
                    class="mono"
                    [value]="inputCvv()"
                    (input)="inputCvv.set($any($event.target).value)"
                    placeholder="123"
                    autocomplete="cc-csc"
                    maxlength="4"
                    required
                  />
                </div>
              </div>

              @if (signInError()) {
                <div class="form-error" role="alert">
                  <p class="error-msg small">{{ signInError() }}</p>
                </div>
              }

              <button
                type="submit"
                class="btn btn-signin"
                [disabled]="signingIn() || !canSubmitSignIn()"
              >
                {{ signingIn() ? 'Signing in…' : 'Sign in' }}
              </button>
            </form>

            <div class="divider">
              <span class="divider-text faint small">or</span>
            </div>

            <!-- Open personal card -->
            <div class="personal-card-section">
              <h3 class="small-title">Open a card of your own</h3>
              <p class="muted small">
                Create a personal test card with a $1,000 debit balance or a $1,000 credit limit.
              </p>
              <div class="open-buttons-row">
                <button
                  type="button"
                  class="btn btn-secondary btn-sm"
                  [disabled]="openingCard()"
                  (click)="openMyOwnCard('DEBIT')"
                >
                  Open Debit card ($1,000)
                </button>
                <button
                  type="button"
                  class="btn btn-secondary btn-sm"
                  [disabled]="openingCard()"
                  (click)="openMyOwnCard('CREDIT')"
                >
                  Open Credit card ($1,000 limit)
                </button>
              </div>
            </div>
          </section>

          <!-- Test cards quick fill -->
          <aside class="card helper-card" aria-labelledby="quick-fill-heading">
            <h2 id="quick-fill-heading" class="card-title">Quick fill with a test card</h2>
            <p class="muted small">
              Click any card below to pre-fill the sign-in form:
            </p>

            @if (loadingTestCards()) {
              <p class="muted small">Loading test cards…</p>
            } @else {
              <div class="quick-cards-list">
                @for (tc of testCards(); track tc.number) {
                  <button
                    type="button"
                    class="quick-card-btn"
                    (click)="fillTestCard(tc)"
                  >
                    <div class="quick-card-head">
                      <span class="badge">{{ tc.kind }}</span>
                      <span class="faint small">{{ tc.outcome }}</span>
                    </div>
                    <span class="mono card-digits-line">{{ formatCardNumber(tc.number) }}</span>
                    <span class="faint small">Exp: {{ tc.expiry }} &middot; CVV: {{ tc.cvv2 }}</span>
                  </button>
                }
              </div>
            }
          </aside>
        </div>
      }

      <!-- SIGNED-IN STATE -->
      @if (api.isAuthenticated()) {
        <!-- NEWLY ISSUED CARD BANNER (SHOWN ONCE) -->
        @if (newlyIssuedCard(); as issued) {
          <section class="card card-one-time-banner" aria-labelledby="heading-new-card">
            <div class="one-time-header">
              <h2 id="heading-new-card" class="card-title">Your new card details</h2>
              <span class="badge">Shown once</span>
            </div>
            <p class="one-time-warning">
              These card details are shown only once. Make sure to copy them now before closing this page.
            </p>

            <div class="details-grid">
              <div class="detail-cell">
                <span class="faint small">Card number</span>
                <div class="detail-row">
                  <span class="mono num copyable-val">{{ formatCardNumber(issued.pan) }}</span>
                  <app-copy-button [textToCopy]="issued.pan" fieldName="card number" />
                </div>
              </div>

              <div class="detail-cell">
                <span class="faint small">Expiry</span>
                <div class="detail-row">
                  <span class="mono num copyable-val">{{ formatExpiry(issued.expiry) }}</span>
                  <app-copy-button [textToCopy]="formatExpiry(issued.expiry)" fieldName="expiry" />
                </div>
              </div>

              <div class="detail-cell">
                <span class="faint small">Security code (CVV2)</span>
                <div class="detail-row">
                  <span class="mono num copyable-val">{{ issued.cvv2 }}</span>
                  <app-copy-button [textToCopy]="issued.cvv2" fieldName="security code" />
                </div>
              </div>
            </div>

            <!-- Activation step if required (after lost/stolen card) -->
            @if (needsActivation()) {
              <div class="activation-box card-subtle">
                <h3 class="card-title">Activate your replacement card</h3>
                <p class="muted small">
                  Type the security code (CVV) from above to activate this new card.
                </p>
                <div class="activate-row">
                  <input
                    type="password"
                    class="mono activate-input"
                    maxlength="4"
                    placeholder="CVV"
                    [value]="activateCvv()"
                    (input)="activateCvv.set($any($event.target).value)"
                  />
                  <button
                    type="button"
                    class="btn btn-sm"
                    [disabled]="activating() || activateCvv().length < 3"
                    (click)="submitActivation()"
                  >
                    {{ activating() ? 'Activating…' : 'Activate card' }}
                  </button>
                </div>
                @if (activationError()) {
                  <p class="error-msg small">{{ activationError() }}</p>
                }
              </div>
            }

            <div class="one-time-actions">
              <button
                type="button"
                class="btn btn-secondary btn-sm"
                (click)="dismissNewlyIssued()"
              >
                I have copied these details
              </button>
            </div>
          </section>
        }

        <!-- PENDING CONFIRMATIONS (PROMINENT AT TOP) -->
        @if (authentications().length > 0) {
          <section class="card authentications-card" aria-labelledby="heading-confirmations">
            <div class="auth-header-row">
              <h2 id="heading-confirmations" class="card-title">Pending step-up confirmations</h2>
              <span class="badge" title="Auto-refreshes every 3 seconds">Live 3s</span>
            </div>

            <div class="auths-list">
              @for (auth of authentications(); track auth.id) {
                <div class="auth-item">
                  <div class="auth-main-col">
                    <p class="auth-prompt">
                      Confirm a payment:
                      <strong>{{ formatMoney(auth.amountCents) }}</strong>
                      to
                      <strong>{{ auth.merchant }}</strong>.
                      Code <strong class="mono num otp-highlight">{{ auth.otp }}</strong>
                    </p>
                    <span class="auth-time faint small">
                      Valid until {{ auth.expiresAt | date: 'mediumTime' }}
                    </span>
                  </div>

                  <div class="auth-actions">
                    <button
                      type="button"
                      class="btn btn-sm btn-approve"
                      [disabled]="processingAuthId() === auth.id"
                      (click)="confirmAuth(auth.id, true)"
                    >
                      Approve
                    </button>
                    <button
                      type="button"
                      class="btn btn-secondary btn-sm"
                      [disabled]="processingAuthId() === auth.id"
                      (click)="confirmAuth(auth.id, false)"
                    >
                      Decline
                    </button>
                  </div>
                </div>
              }
            </div>
          </section>
        }

        @if (loadingCard() && !card()) {
          <div class="card" role="status">
            <p class="muted">Loading cardholder details…</p>
          </div>
        } @else if (card(); as c) {
          <div class="dashboard-grid">
            <!-- Left column: Visual Card and Balances, Actions -->
            <div class="card-column">
              <!-- Visual Card -->
              <section class="card-visual-wrapper">
                <div
                  class="card-visual"
                  [class.card-blocked]="c.status === 'BLOCKED' || c.status === 'LOST' || c.status === 'STOLEN'"
                >
                  <div class="card-visual-top">
                    <div>
                      <span class="bank-title">Kestrel Bank</span>
                      <span class="card-tagline faint small">Cardholder</span>
                    </div>
                    <span class="card-kind-tag">{{ c.kind }}</span>
                  </div>

                  <div class="card-pan-line mono num">
                    &bull;&bull;&bull;&bull; &bull;&bull;&bull;&bull; &bull;&bull;&bull;&bull; {{ c.last4 }}
                  </div>

                  <div class="card-visual-bottom">
                    <div>
                      <span class="faint small exp-label">Expires</span>
                      <span class="mono num exp-val">{{ c.expiry }}</span>
                    </div>
                    <span
                      class="badge"
                      [class.badge-approved]="c.status === 'ACTIVE'"
                      [class.badge-declined]="c.status === 'BLOCKED' || c.status === 'LOST' || c.status === 'STOLEN'"
                      [class.badge-unknown]="c.status === 'INACTIVE'"
                    >
                      {{ cardStatusLabel(c.status) }}
                    </span>
                  </div>
                </div>

                <!-- Balances panel -->
                <div class="card balances-card">
                  <h2 class="card-title">Balance and limits</h2>
                  <div class="balances-grid">
                    @if (c.kind === 'DEBIT') {
                      <div class="balance-item">
                        <span class="muted small">Available to spend</span>
                        <span class="mono num balance-val">{{ formatMoney(c.availableCents) }}</span>
                      </div>
                      <div class="balance-item">
                        <span class="muted small">Account balance</span>
                        <span class="mono num balance-sub">{{ formatMoney(c.balanceCents) }}</span>
                      </div>
                      @if (c.heldCents > 0) {
                        <div class="balance-item">
                          <span class="muted small">Pending holds</span>
                          <span class="mono num balance-sub">{{ formatMoney(c.heldCents) }}</span>
                        </div>
                      }
                    } @else {
                      <div class="balance-item">
                        <span class="muted small">What you owe (balance)</span>
                        <span class="mono num balance-val">{{ formatMoney(c.balanceCents) }}</span>
                      </div>
                      <div class="balance-item">
                        <span class="muted small">Available credit</span>
                        <span class="mono num balance-sub">{{ formatMoney(c.availableCents) }}</span>
                      </div>
                      <div class="balance-item">
                        <span class="muted small">Credit limit</span>
                        <span class="mono num balance-sub">{{ formatMoney(c.creditLimitCents) }}</span>
                      </div>
                      @if (c.heldCents > 0) {
                        <div class="balance-item">
                          <span class="muted small">Pending holds</span>
                          <span class="mono num balance-sub">{{ formatMoney(c.heldCents) }}</span>
                        </div>
                      }
                    }
                  </div>
                </div>

                <!-- Card action buttons -->
                <div class="card card-actions-panel">
                  <h2 class="card-title">Card actions</h2>
                  <div class="actions-buttons-row">
                    @if (c.status === 'ACTIVE') {
                      <button
                        type="button"
                        class="btn btn-secondary btn-sm"
                        [disabled]="freezing()"
                        (click)="freezeCard()"
                      >
                        {{ freezing() ? 'Updating…' : 'Freeze card' }}
                      </button>
                    } @else if (c.status === 'BLOCKED') {
                      <button
                        type="button"
                        class="btn btn-secondary btn-sm"
                        [disabled]="freezing()"
                        (click)="unfreezeCard()"
                      >
                        {{ freezing() ? 'Updating…' : 'Unfreeze card' }}
                      </button>
                    }

                    <button
                      type="button"
                      class="btn btn-secondary btn-sm btn-report-lost"
                      (click)="showReportLostPrompt.set(true)"
                    >
                      Report lost or stolen
                    </button>
                  </div>

                  @if (showReportLostPrompt()) {
                    <div class="report-lost-dialog card-subtle" role="region" aria-label="Report card dialog">
                      <p class="small">
                        <strong>Your card stops working now. You'll get a new number.</strong>
                      </p>
                      <div class="report-buttons-row">
                        <button
                          type="button"
                          class="btn btn-sm"
                          [disabled]="reportingLost()"
                          (click)="confirmReportLost(false)"
                        >
                          Report lost
                        </button>
                        <button
                          type="button"
                          class="btn btn-sm"
                          [disabled]="reportingLost()"
                          (click)="confirmReportLost(true)"
                        >
                          Report stolen
                        </button>
                        <button
                          type="button"
                          class="btn btn-secondary btn-sm"
                          [disabled]="reportingLost()"
                          (click)="showReportLostPrompt.set(false)"
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  }
                </div>
              </section>

              <!-- Card Controls Panel -->
              <section class="card controls-card" aria-labelledby="controls-heading">
                <h2 id="controls-heading" class="card-title">Card controls and limits</h2>
                <p class="muted small">
                  Configure real-time spending rules and merchant categories.
                </p>

                <form (submit)="saveControls($event)" class="controls-form">
                  <div class="toggle-field">
                    <label class="toggle-label">
                      <input
                        type="checkbox"
                        [checked]="controlsOnline()"
                        (change)="controlsOnline.set($any($event.target).checked)"
                      />
                      <span>Allow online payments</span>
                    </label>
                  </div>

                  <div class="form-row-2">
                    <div class="field">
                      <label for="limit-single">Single payment limit ($)</label>
                      <input
                        id="limit-single"
                        type="text"
                        class="mono"
                        placeholder="No limit"
                        [value]="controlsSingleLimit()"
                        (input)="controlsSingleLimit.set($any($event.target).value)"
                      />
                    </div>
                    <div class="field">
                      <label for="limit-daily">Daily spend limit ($)</label>
                      <input
                        id="limit-daily"
                        type="text"
                        class="mono"
                        placeholder="No limit"
                        [value]="controlsDailyLimit()"
                        (input)="controlsDailyLimit.set($any($event.target).value)"
                      />
                    </div>
                  </div>

                  <fieldset class="categories-fieldset">
                    <legend class="categories-legend">Block categories by name</legend>
                    <div class="categories-list">
                      @for (cat of mccCategories; track cat.code) {
                        <label class="checkbox-label">
                          <input
                            type="checkbox"
                            [checked]="isMccBlocked(cat.code)"
                            (change)="toggleMcc(cat.code, $any($event.target).checked)"
                          />
                          <span>{{ cat.label }} ({{ cat.code }})</span>
                        </label>
                      }
                    </div>
                  </fieldset>

                  @if (controlsError()) {
                    <p class="error-msg small">{{ controlsError() }}</p>
                  }
                  @if (controlsSuccess()) {
                    <p class="success-msg small">{{ controlsSuccess() }}</p>
                  }

                  <button
                    type="submit"
                    class="btn btn-sm btn-save-controls"
                    [disabled]="savingControls()"
                  >
                    {{ savingControls() ? 'Saving…' : 'Save controls' }}
                  </button>
                </form>
              </section>
            </div>

            <!-- Right column: Transactions -->
            <section class="card transactions-card" aria-labelledby="tx-heading">
              <div class="tx-header">
                <div>
                  <h2 id="tx-heading" class="card-title">Recent transactions</h2>
                  <p class="muted small">Purchases and refunds on this card.</p>
                </div>
                <button
                  type="button"
                  class="btn btn-secondary btn-sm"
                  [disabled]="loadingTx()"
                  (click)="loadTransactions()"
                >
                  {{ loadingTx() ? 'Refreshing…' : 'Refresh' }}
                </button>
              </div>

              @if (transactions().length === 0) {
                <p class="muted small">No transactions recorded on this card yet.</p>
              } @else {
                <div class="transactions-list">
                  @for (tx of transactions(); track tx.reference) {
                    <article class="tx-item">
                      <div class="tx-main-row">
                        <div class="tx-desc-col">
                          <span class="tx-merchant">{{ tx.merchant }}</span>
                          <span class="mono small faint">{{ tx.at | date: 'medium' }}</span>
                        </div>
                        <div class="tx-amount-col">
                          <span class="mono num tx-amount" [class.tx-refund]="tx.kind === 'REFUND'">
                            {{ tx.kind === 'REFUND' ? '+' : '' }}{{ formatMoney(tx.amountCents) }}
                          </span>
                          <span
                            class="badge"
                            [class.badge-approved]="tx.status === 'APPROVED' || tx.status === 'CLEARED'"
                            [class.badge-declined]="tx.status === 'DECLINED'"
                            [class.badge-unknown]="tx.status === 'REVERSED' || tx.status === 'EXPIRED'"
                          >
                            {{ formatTxStatus(tx) }}
                          </span>
                        </div>
                      </div>

                      <div class="tx-meta-row">
                        <div class="tx-meta-left">
                          <a [routerLink]="['/trace', tx.reference]" class="small">View trace &rarr;</a>
                          @if (tx.authenticated) {
                            <span class="badge badge-approved small">Confirmed in the app</span>
                          }
                          @if (tx.fraudScore !== undefined && tx.fraudScore !== null) {
                            <span class="small faint">
                              Risk score {{ tx.fraudScore }}{{ tx.riskReasons ? ': ' + tx.riskReasons : '' }}
                            </span>
                          }
                        </div>

                        <div class="tx-meta-right">
                          @if (tx.disputable) {
                            <button
                              type="button"
                              class="dispute-link small"
                              (click)="openDispute(tx)"
                            >
                              Dispute
                            </button>
                          }
                        </div>
                      </div>

                      <!-- Inline dispute form -->
                      @if (activeDisputeRef() === tx.reference) {
                        <div class="dispute-box card-subtle" role="region" aria-label="Dispute purchase form">
                          <label [for]="'dispute-reason-' + tx.reference">
                            Dispute reason
                          </label>
                          <div class="dispute-form-row">
                            <select
                              [id]="'dispute-reason-' + tx.reference"
                              [value]="selectedDisputeReason()"
                              (change)="selectedDisputeReason.set($any($event.target).value)"
                            >
                              @for (r of disputeReasons; track r.code) {
                                <option [value]="r.code">{{ r.label }}</option>
                              }
                            </select>
                            <button
                              type="button"
                              class="btn btn-sm"
                              [disabled]="disputing()"
                              (click)="submitDispute(tx)"
                            >
                              {{ disputing() ? 'Submitting…' : 'Submit dispute' }}
                            </button>
                            <button
                              type="button"
                              class="btn btn-secondary btn-sm"
                              [disabled]="disputing()"
                              (click)="activeDisputeRef.set(null)"
                            >
                              Cancel
                            </button>
                          </div>
                          @if (disputeError()) {
                            <p class="error-msg small">{{ disputeError() }}</p>
                          }
                        </div>
                      }
                      @if (disputeSuccessRef() === tx.reference) {
                        <div class="dispute-success-banner small" role="status">
                          Dispute submitted to Kestrel Bank.
                        </div>
                      }
                    </article>
                  }
                </div>
              }
            </section>
          </div>
        }
      }
    </main>
  `,
  styles: `
    .page-header { display: flex; justify-content: space-between; align-items: baseline; margin: 16px 0 24px; flex-wrap: wrap; gap: 12px; }
    .card-title { font-size: 1.05rem; margin-bottom: 4px; }
    .shared-card-alert { background-color: var(--status-unknown-bg); border: 1px solid var(--status-unknown); margin-bottom: 20px; display: flex; flex-direction: column; gap: 10px; }
    .shared-card-msg { margin: 0; font-weight: 500; color: var(--ink); }
    .shared-card-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .auth-layout { display: grid; grid-template-columns: 1fr; gap: 24px; }
    @media (min-width: 860px) { .auth-layout { grid-template-columns: 1.15fr 0.85fr; } }
    .signin-form { display: flex; flex-direction: column; gap: 14px; margin-top: 16px; }
    .form-row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .btn-signin { width: 100%; margin-top: 4px; }
    .divider { display: flex; align-items: center; text-align: center; margin: 20px 0; }
    .divider::before, .divider::after { content: ''; flex: 1; border-bottom: 1px solid var(--line); }
    .divider-text { padding: 0 10px; }
    .small-title { font-size: 0.9375rem; margin: 0 0 4px; }
    .open-buttons-row { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
    .quick-cards-list { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
    .quick-card-btn { padding: 10px 12px; background-color: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius-sm); display: flex; flex-direction: column; align-items: flex-start; gap: 4px; cursor: pointer; text-align: left; font-family: inherit; transition: border-color var(--duration-fast) var(--ease-out), background-color var(--duration-fast) var(--ease-out); }
    .quick-card-btn:hover { border-color: var(--accent); background-color: var(--accent-soft); }
    .quick-card-head { display: flex; align-items: center; gap: 8px; }
    .card-digits-line { font-size: 0.875rem; font-weight: 600; color: var(--ink); }
    .card-one-time-banner { border: 1.5px solid var(--accent); background-color: var(--accent-soft); margin-bottom: 24px; }
    .one-time-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
    .one-time-warning { color: var(--ink); font-weight: 500; margin-bottom: 16px; }
    .details-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; margin-bottom: 16px; }
    .detail-cell { background-color: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
    .detail-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .copyable-val { font-size: 1rem; font-weight: 600; color: var(--ink); }
    .activation-box { margin-bottom: 16px; padding: 12px; }
    .activate-row { display: flex; gap: 8px; align-items: center; margin-top: 8px; }
    .activate-input { max-width: 100px; }
    .authentications-card { border: 1.5px solid var(--accent); margin-bottom: 24px; background-color: var(--surface); }
    .auth-header-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
    .auths-list { display: flex; flex-direction: column; gap: 10px; }
    .auth-item { padding: 12px; background-color: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius-sm); display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
    .auth-prompt { margin: 0 0 2px; font-size: 0.9375rem; color: var(--ink); }
    .otp-highlight { font-size: 1.1rem; letter-spacing: 0.05em; color: var(--accent); }
    .auth-actions { display: flex; align-items: center; gap: 8px; }
    .btn-approve { background-color: var(--status-approved); color: #fff; }
    .btn-approve:hover:not(:disabled) { background-color: #166534; color: #fff; }
    .dashboard-grid { display: grid; grid-template-columns: 1fr; gap: 24px; }
    @media (min-width: 960px) { .dashboard-grid { grid-template-columns: 380px 1fr; } }
    .card-column { display: flex; flex-direction: column; gap: 20px; }
    .card-visual-wrapper { display: flex; flex-direction: column; gap: 12px; }
    .card-visual { background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%); color: #f8fafc; border-radius: var(--radius-lg); padding: 20px; min-height: 190px; display: flex; flex-direction: column; justify-content: space-between; box-shadow: var(--shadow); border: 1px solid rgba(255, 255, 255, 0.1); }
    .card-blocked { filter: grayscale(0.85); opacity: 0.85; }
    .card-visual-top { display: flex; justify-content: space-between; align-items: flex-start; }
    .bank-title { font-size: 1.1rem; font-weight: 600; letter-spacing: -0.01em; display: block; }
    .card-tagline { color: #94a3b8; }
    .card-kind-tag { font-size: 0.75rem; font-weight: 600; padding: 2px 8px; border-radius: var(--radius-sm); background-color: rgba(255, 255, 255, 0.15); color: #f8fafc; }
    .card-pan-line { font-size: 1.25rem; letter-spacing: 0.08em; font-weight: 600; }
    .card-visual-bottom { display: flex; justify-content: space-between; align-items: flex-end; }
    .exp-label { color: #94a3b8; display: block; }
    .exp-val { font-weight: 600; font-size: 0.875rem; }
    .balances-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 10px; }
    .balance-item { display: flex; flex-direction: column; gap: 2px; }
    .balance-val { font-size: 1.25rem; font-weight: 600; }
    .balance-sub { font-size: 0.9375rem; font-weight: 500; }
    .actions-buttons-row { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
    .report-lost-dialog { margin-top: 12px; padding: 12px; }
    .report-buttons-row { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
    .controls-form { display: flex; flex-direction: column; gap: 14px; margin-top: 12px; }
    .toggle-label, .checkbox-label { display: inline-flex; align-items: center; gap: 8px; font-size: 0.875rem; cursor: pointer; font-weight: normal; margin-bottom: 0; }
    .toggle-label input, .checkbox-label input { width: auto; min-height: auto; margin: 0; }
    .categories-fieldset { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 12px; margin: 0; }
    .categories-legend { font-size: 0.8125rem; font-weight: 600; color: var(--ink-muted); padding: 0 4px; }
    .categories-list { display: flex; flex-direction: column; gap: 8px; margin-top: 4px; }
    .btn-save-controls { align-self: flex-start; }
    .tx-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; gap: 12px; }
    .transactions-list { display: flex; flex-direction: column; gap: 10px; }
    .tx-item { padding: 10px 12px; background-color: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius-sm); display: flex; flex-direction: column; gap: 6px; }
    .tx-main-row { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
    .tx-desc-col { display: flex; flex-direction: column; gap: 2px; }
    .tx-merchant { font-weight: 600; color: var(--ink); }
    .tx-amount-col { display: flex; align-items: center; gap: 8px; }
    .tx-amount { font-weight: 600; }
    .tx-refund { color: var(--status-approved); }
    .tx-meta-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; padding-top: 4px; border-top: 1px solid var(--line); }
    .tx-meta-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .dispute-link { background: none; border: none; padding: 0; color: var(--ink-faint); text-decoration: underline; cursor: pointer; font-family: inherit; }
    .dispute-link:hover { color: var(--accent); }
    .dispute-box { margin-top: 8px; padding: 10px 12px; }
    .dispute-form-row { display: flex; gap: 8px; align-items: center; margin-top: 6px; flex-wrap: wrap; }
    .dispute-form-row select { flex: 1 1 200px; }
    .dispute-success-banner { color: var(--status-approved); font-weight: 500; margin-top: 4px; }
    .error-msg { color: var(--status-declined); font-weight: 500; margin: 0; }
    .success-msg { color: var(--status-approved); font-weight: 500; margin: 0; }
  `,
})
export class Card implements OnInit, OnDestroy {
  protected readonly api = inject(CardholderApi);
  private readonly generalApi = inject(Api);

  readonly mccCategories = BLOCKED_MCC_CATEGORIES;
  readonly disputeReasons = DISPUTE_REASONS;

  // Sign-in state
  protected readonly inputNumber = signal<string>('');
  protected readonly inputExpiry = signal<string>('');
  protected readonly inputCvv = signal<string>('');
  protected readonly signingIn = signal<boolean>(false);
  protected readonly signInError = signal<string>('');
  protected readonly openingCard = signal<boolean>(false);

  // Quick fill test cards
  protected readonly testCards = signal<TestCard[]>([]);
  protected readonly loadingTestCards = signal<boolean>(false);

  // Authenticated state
  protected readonly card = signal<CardholderCard | null>(null);
  protected readonly authentications = signal<CardholderAuthentication[]>([]);
  protected readonly transactions = signal<CardholderTransaction[]>([]);
  protected readonly controls = signal<CardholderControls | null>(null);
  protected readonly loadingCard = signal<boolean>(false);
  protected readonly loadingTx = signal<boolean>(false);
  protected readonly freezing = signal<boolean>(false);
  protected readonly sharedCardNotice = signal<boolean>(false);

  // Newly issued card details
  protected readonly newlyIssuedCard = signal<IssuedCardDetails | null>(null);
  protected readonly needsActivation = signal<boolean>(false);
  protected readonly activateCvv = signal<string>('');
  protected readonly activating = signal<boolean>(false);
  protected readonly activationError = signal<string>('');

  // Report lost
  protected readonly showReportLostPrompt = signal<boolean>(false);
  protected readonly reportingLost = signal<boolean>(false);

  // Controls form state
  protected readonly controlsOnline = signal<boolean>(true);
  protected readonly controlsSingleLimit = signal<string>('');
  protected readonly controlsDailyLimit = signal<string>('');
  protected readonly controlsBlockedMccs = signal<string[]>([]);
  protected readonly savingControls = signal<boolean>(false);
  protected readonly controlsError = signal<string>('');
  protected readonly controlsSuccess = signal<string>('');

  // Dispute state
  protected readonly activeDisputeRef = signal<string | null>(null);
  protected readonly selectedDisputeReason = signal<string>('1040');
  protected readonly disputing = signal<boolean>(false);
  protected readonly disputeError = signal<string>('');
  protected readonly disputeSuccessRef = signal<string | null>(null);

  // Authentications state
  protected readonly processingAuthId = signal<string | null>(null);
  private authTimer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    if (this.api.isAuthenticated()) {
      void this.loadCardholderData();
      this.startAuthPolling();
    } else {
      void this.loadTestCards();
    }
  }

  ngOnDestroy(): void {
    this.stopAuthPolling();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (typeof document === 'undefined') {
      return;
    }
    if (document.visibilityState === 'visible' && this.api.isAuthenticated()) {
      void this.fetchAuthentications();
      if (!this.authTimer) {
        this.startAuthPolling();
      }
    } else {
      this.stopAuthPolling();
    }
  }

  protected onNumberInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const clean = input.value.replace(/\s+/g, '');
    this.inputNumber.set(groupCardNumber(clean));
  }

  protected canSubmitSignIn(): boolean {
    const num = this.inputNumber().replace(/\s+/g, '');
    return num.length >= 12 && this.inputExpiry().length >= 4 && this.inputCvv().length >= 3;
  }

  protected async signIn(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitSignIn()) {
      return;
    }
    this.signingIn.set(true);
    this.signInError.set('');
    this.sharedCardNotice.set(false);

    try {
      await this.api.createSession({
        number: this.inputNumber().replace(/\s+/g, ''),
        expiry: this.inputExpiry(),
        cvv2: this.inputCvv(),
      });
      await this.loadCardholderData();
      this.startAuthPolling();
    } catch (err: unknown) {
      this.signInError.set(messageOf(err));
    } finally {
      this.signingIn.set(false);
    }
  }

  protected signOut(): void {
    this.stopAuthPolling();
    this.api.clearSession();
    this.card.set(null);
    this.authentications.set([]);
    this.transactions.set([]);
    this.newlyIssuedCard.set(null);
    this.sharedCardNotice.set(false);
    void this.loadTestCards();
  }

  protected async loadTestCards(): Promise<void> {
    this.loadingTestCards.set(true);
    try {
      const cards = await this.generalApi.getTestCards();
      this.testCards.set(cards);
    } catch {
      // Ignore background errors
    } finally {
      this.loadingTestCards.set(false);
    }
  }

  protected fillTestCard(tc: TestCard): void {
    this.inputNumber.set(groupCardNumber(tc.number));
    this.inputExpiry.set(tc.expiry);
    this.inputCvv.set(tc.cvv2);
    this.signInError.set('');
  }

  protected async openMyOwnCard(kind: 'DEBIT' | 'CREDIT'): Promise<void> {
    this.openingCard.set(true);
    this.signInError.set('');
    this.sharedCardNotice.set(false);

    try {
      const res = await this.api.createCard({ kind });
      this.newlyIssuedCard.set(res.card);
      this.needsActivation.set(false);
      await this.loadCardholderData();
      this.startAuthPolling();
    } catch (err: unknown) {
      this.signInError.set(messageOf(err));
    } finally {
      this.openingCard.set(false);
    }
  }

  protected dismissNewlyIssued(): void {
    this.newlyIssuedCard.set(null);
    this.needsActivation.set(false);
  }

  private async loadCardholderData(): Promise<void> {
    this.loadingCard.set(true);
    try {
      const [c, txs, ctrls] = await Promise.all([
        this.api.getCard(),
        this.api.getTransactions(),
        this.api.getControls(),
      ]);
      this.card.set(c);
      this.transactions.set(txs);
      this.controls.set(ctrls);
      this.syncControlsState(ctrls);
      await this.fetchAuthentications();
    } catch (err: unknown) {
      if (!this.api.isAuthenticated()) {
        this.signOut();
      }
    } finally {
      this.loadingCard.set(false);
    }
  }

  private syncControlsState(ctrls: CardholderControls): void {
    this.controlsOnline.set(ctrls.onlineEnabled);
    this.controlsSingleLimit.set(
      ctrls.singleLimitCents ? (ctrls.singleLimitCents / 100).toFixed(2) : '',
    );
    this.controlsDailyLimit.set(
      ctrls.dailyLimitCents ? (ctrls.dailyLimitCents / 100).toFixed(2) : '',
    );
    this.controlsBlockedMccs.set(ctrls.blockedMccs ?? []);
    this.controlsError.set('');
    this.controlsSuccess.set('');
  }

  private async fetchAuthentications(): Promise<void> {
    if (!this.api.isAuthenticated()) {
      return;
    }
    try {
      const auths = await this.api.getAuthentications();
      this.authentications.set(auths);
    } catch (err: unknown) {
      if (!this.api.isAuthenticated()) {
        this.signOut();
      }
    }
  }

  private startAuthPolling(): void {
    this.stopAuthPolling();
    void this.fetchAuthentications();
    if (typeof window !== 'undefined') {
      this.authTimer = setInterval(() => {
        if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
          void this.fetchAuthentications();
        }
      }, 3000);
    }
  }

  private stopAuthPolling(): void {
    if (this.authTimer !== null) {
      clearInterval(this.authTimer);
      this.authTimer = null;
    }
  }

  protected async confirmAuth(id: string, approve: boolean): Promise<void> {
    this.processingAuthId.set(id);
    try {
      await this.api.approveAuthentication(id, approve);
      await Promise.all([
        this.fetchAuthentications(),
        this.loadCardDetails(),
        this.loadTransactions(),
      ]);
    } catch (err: unknown) {
      // Handled
    } finally {
      this.processingAuthId.set(null);
    }
  }

  protected async loadCardDetails(): Promise<void> {
    try {
      const c = await this.api.getCard();
      this.card.set(c);
    } catch (err: unknown) {
      if (isSharedCardProblem(err)) {
        this.sharedCardNotice.set(true);
      }
    }
  }

  protected async loadTransactions(): Promise<void> {
    this.loadingTx.set(true);
    try {
      const txs = await this.api.getTransactions();
      this.transactions.set(txs);
    } catch {
      // Ignored
    } finally {
      this.loadingTx.set(false);
    }
  }

  protected async freezeCard(): Promise<void> {
    this.freezing.set(true);
    this.sharedCardNotice.set(false);
    try {
      const updated = await this.api.freezeCard();
      this.card.set(updated);
    } catch (err: unknown) {
      if (isSharedCardProblem(err)) {
        this.sharedCardNotice.set(true);
      }
    } finally {
      this.freezing.set(false);
    }
  }

  protected async unfreezeCard(): Promise<void> {
    this.freezing.set(true);
    this.sharedCardNotice.set(false);
    try {
      const updated = await this.api.unfreezeCard();
      this.card.set(updated);
    } catch (err: unknown) {
      if (isSharedCardProblem(err)) {
        this.sharedCardNotice.set(true);
      }
    } finally {
      this.freezing.set(false);
    }
  }

  protected async confirmReportLost(stolen: boolean): Promise<void> {
    this.reportingLost.set(true);
    this.sharedCardNotice.set(false);
    try {
      const res = await this.api.reportLost(stolen);
      this.showReportLostPrompt.set(false);
      this.newlyIssuedCard.set(res.card);
      this.needsActivation.set(true);
      this.activateCvv.set('');
      this.activationError.set('');
      await this.loadCardholderData();
    } catch (err: unknown) {
      this.showReportLostPrompt.set(false);
      if (isSharedCardProblem(err)) {
        this.sharedCardNotice.set(true);
      }
    } finally {
      this.reportingLost.set(false);
    }
  }

  protected async submitActivation(): Promise<void> {
    this.activating.set(true);
    this.activationError.set('');
    try {
      await this.api.activateCard(this.activateCvv());
      this.needsActivation.set(false);
      await this.loadCardDetails();
    } catch (err: unknown) {
      this.activationError.set(messageOf(err));
    } finally {
      this.activating.set(false);
    }
  }

  protected isMccBlocked(code: string): boolean {
    return this.controlsBlockedMccs().includes(code);
  }

  protected toggleMcc(code: string, checked: boolean): void {
    const current = [...this.controlsBlockedMccs()];
    if (checked && !current.includes(code)) {
      current.push(code);
    } else if (!checked) {
      const idx = current.indexOf(code);
      if (idx !== -1) {
        current.splice(idx, 1);
      }
    }
    this.controlsBlockedMccs.set(current);
  }

  protected async saveControls(event: Event): Promise<void> {
    event.preventDefault();
    this.savingControls.set(true);
    this.controlsError.set('');
    this.controlsSuccess.set('');
    this.sharedCardNotice.set(false);

    const singleRes = parseDollarLimit(this.controlsSingleLimit());
    if (!singleRes.valid) {
      this.controlsError.set(`Single payment limit: ${singleRes.error}`);
      this.savingControls.set(false);
      return;
    }

    const dailyRes = parseDollarLimit(this.controlsDailyLimit());
    if (!dailyRes.valid) {
      this.controlsError.set(`Daily spend limit: ${dailyRes.error}`);
      this.savingControls.set(false);
      return;
    }

    try {
      const updated = await this.api.updateControls({
        onlineEnabled: this.controlsOnline(),
        singleLimitCents: singleRes.cents ?? null,
        dailyLimitCents: dailyRes.cents ?? null,
        blockedMccs: this.controlsBlockedMccs(),
      });
      this.controls.set(updated);
      this.syncControlsState(updated);
      this.controlsSuccess.set('Controls saved successfully.');
    } catch (err: unknown) {
      if (isSharedCardProblem(err)) {
        this.sharedCardNotice.set(true);
      } else {
        this.controlsError.set(messageOf(err));
      }
    } finally {
      this.savingControls.set(false);
    }
  }

  protected openDispute(tx: CardholderTransaction): void {
    this.activeDisputeRef.set(tx.reference);
    this.selectedDisputeReason.set('1040');
    this.disputeError.set('');
    this.disputeSuccessRef.set(null);
  }

  protected async submitDispute(tx: CardholderTransaction): Promise<void> {
    this.disputing.set(true);
    this.disputeError.set('');
    this.disputeSuccessRef.set(null);

    try {
      await this.api.createDispute({
        reference: tx.reference,
        reason: this.selectedDisputeReason(),
      });
      this.activeDisputeRef.set(null);
      this.disputeSuccessRef.set(tx.reference);
      await Promise.all([this.loadTransactions(), this.loadCardDetails()]);
    } catch (err: unknown) {
      this.disputeError.set(messageOf(err));
    } finally {
      this.disputing.set(false);
    }
  }

  protected formatCardNumber(num: string | null | undefined): string {
    return groupCardNumber(num);
  }

  protected formatMoney(cents: number | null | undefined): string {
    return formatCents(cents);
  }

  protected formatTxStatus(tx: CardholderTransaction): string {
    return formatTransactionStatus(tx.status, tx.responseText);
  }

  protected cardStatusLabel(status: CardholderCardStatus): string {
    switch (status) {
      case 'ACTIVE':
        return 'Active';
      case 'BLOCKED':
        return 'Frozen';
      case 'LOST':
        return 'Lost';
      case 'STOLEN':
        return 'Stolen';
      case 'INACTIVE':
        return 'Inactive';
      default:
        return status;
    }
  }

  protected formatExpiry(raw: string | null | undefined): string {
    if (!raw) return '';
    if (raw.length === 4 && !raw.includes('/')) {
      // YYMM -> MM/YY or keep
      return `${raw.substring(2, 4)}/${raw.substring(0, 2)}`;
    }
    return raw;
  }
}
