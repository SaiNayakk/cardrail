import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Api } from '../core/api';
import {
  BOOK_PARTIES,
  BookAccountSections,
  PartyBookMeta,
  groupBookAccounts,
} from '../core/books';
import { formatCents } from '../core/money';
import { PartyBook } from '../core/types';

interface PartyBookState {
  meta: PartyBookMeta;
  book: PartyBook | null;
  loading: boolean;
  error: string;
}

@Component({
  selector: 'app-books',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="books-header">
        <div>
          <h1>Books</h1>
          <p class="lede">
            Every payment moves money from one account to another using double-entry bookkeeping, so each book always adds up to zero. Debits are positive and credits are negative. Accounts starting with <code>due:railnet</code> track what Railnet and member banks owe each other until the next settlement.
          </p>
        </div>
        <div class="header-actions">
          <span class="badge" title="Auto-refreshes every 10 seconds while active">Live 10s</span>
          <button
            type="button"
            class="btn btn-secondary btn-sm"
            [disabled]="refreshing()"
            (click)="refreshAll()"
          >
            {{ refreshing() ? 'Refreshing…' : 'Refresh' }}
          </button>
        </div>
      </div>

      <div class="books-grid">
        @for (party of parties; track party.id) {
          @let state = partyStates()[party.id];
          <section class="card book-card" [attr.aria-labelledby]="'party-title-' + party.id">
            <div class="book-card-head">
              <div>
                <h2 [id]="'party-title-' + party.id" class="party-name">{{ party.name }}</h2>
                <p class="muted small party-role">{{ party.role }}</p>
              </div>
              @if (state?.book; as b) {
                <span
                  class="badge"
                  [class.badge-approved]="b.total === 0"
                  [class.badge-declined]="b.total !== 0"
                >
                  {{ b.total === 0 ? 'Balanced' : 'Unbalanced' }}
                </span>
              }
            </div>

            @if (state?.loading && !state?.book) {
              <div class="book-status" role="status">
                <p class="muted small">Loading ledger accounts…</p>
              </div>
            } @else if (state?.error; as err) {
              <div class="book-error" role="alert">
                <p class="error-msg small">{{ err }}</p>
                <button type="button" class="btn btn-secondary btn-sm" (click)="loadParty(party.id)">
                  Retry
                </button>
              </div>
            } @else if (state?.book; as book) {
              @let sections = getSections(book.accounts);
              <div class="accounts-container">
                <!-- 1. Bank and Due accounts first -->
                @if (sections.bankAndDue.length > 0) {
                  <div class="accounts-section">
                    <span class="section-micro-label">Bank &amp; settlement</span>
                    <ul class="accounts-list">
                      @for (acc of sections.bankAndDue; track acc.name) {
                        <li class="account-row">
                          <span class="account-name mono">{{ acc.name }}</span>
                          <span class="account-balance mono num" [class.balance-credit]="acc.balance < 0">
                            {{ formatMoney(acc.balance) }}
                          </span>
                        </li>
                      }
                    </ul>
                  </div>
                }

                <!-- 2. Income and Expense accounts next -->
                @if (sections.incomeAndExpense.length > 0) {
                  <div class="accounts-section">
                    <span class="section-micro-label">Income &amp; fees</span>
                    <ul class="accounts-list">
                      @for (acc of sections.incomeAndExpense; track acc.name) {
                        <li class="account-row">
                          <span class="account-name mono">{{ acc.name }}</span>
                          <span class="account-balance mono num" [class.balance-credit]="acc.balance < 0">
                            {{ formatMoney(acc.balance) }}
                          </span>
                        </li>
                      }
                    </ul>
                  </div>
                }

                <!-- 3. Cardholder accounts (collapsed group) -->
                @if (sections.cardholderGroup.count > 0) {
                  <details class="accounts-group">
                    <summary class="group-summary">
                      <span class="group-title">{{ sections.cardholderGroup.label }}</span>
                      <span class="group-total mono num" [class.balance-credit]="sections.cardholderGroup.total < 0">
                        {{ formatMoney(sections.cardholderGroup.total) }}
                      </span>
                    </summary>
                    <ul class="accounts-list group-list">
                      @for (acc of sections.cardholderGroup.accounts; track acc.name) {
                        <li class="account-row account-sub-row">
                          <span class="account-name mono small faint">{{ acc.name }}</span>
                          <span class="account-balance mono num small" [class.balance-credit]="acc.balance < 0">
                            {{ formatMoney(acc.balance) }}
                          </span>
                        </li>
                      }
                    </ul>
                  </details>
                }

                <!-- 4. Merchant accounts (collapsed group) -->
                @if (sections.merchantGroup.count > 0) {
                  <details class="accounts-group">
                    <summary class="group-summary">
                      <span class="group-title">{{ sections.merchantGroup.label }}</span>
                      <span class="group-total mono num" [class.balance-credit]="sections.merchantGroup.total < 0">
                        {{ formatMoney(sections.merchantGroup.total) }}
                      </span>
                    </summary>
                    <ul class="accounts-list group-list">
                      @for (acc of sections.merchantGroup.accounts; track acc.name) {
                        <li class="account-row account-sub-row">
                          <span class="account-name mono small faint">{{ acc.name }}</span>
                          <span class="account-balance mono num small" [class.balance-credit]="acc.balance < 0">
                            {{ formatMoney(acc.balance) }}
                          </span>
                        </li>
                      }
                    </ul>
                  </details>
                }

                <!-- 5. Other accounts if any -->
                @if (sections.other.length > 0) {
                  <div class="accounts-section">
                    <span class="section-micro-label">Other accounts</span>
                    <ul class="accounts-list">
                      @for (acc of sections.other; track acc.name) {
                        <li class="account-row">
                          <span class="account-name mono">{{ acc.name }}</span>
                          <span class="account-balance mono num" [class.balance-credit]="acc.balance < 0">
                            {{ formatMoney(acc.balance) }}
                          </span>
                        </li>
                      }
                    </ul>
                  </div>
                }
              </div>

              <!-- Book footer with total and clear Balances check mark (text, not emoji) -->
              <div class="book-footer">
                <div class="total-col">
                  <span class="total-label">Total</span>
                  <span class="total-amount mono num">{{ formatMoney(book.total) }}</span>
                </div>
                <div class="check-col">
                  @if (book.total === 0) {
                    <span class="balance-check">
                      <span class="check-symbol" aria-hidden="true">&#10003;</span>
                      <span>Balances</span>
                    </span>
                  } @else {
                    <span class="unbalanced-flag">Unbalanced</span>
                  }
                </div>
              </div>
            }
          </section>
        }
      </div>
    </main>
  `,
  styles: `
    .books-header { display: flex; justify-content: space-between; align-items: flex-start; margin: 16px 0 24px; gap: 16px; flex-wrap: wrap; }
    .books-header div { max-width: 760px; }
    .lede { font-size: 0.9375rem; color: var(--ink-muted); margin-top: 8px; line-height: 1.6; }
    .lede code { font-family: var(--font-mono); font-size: 0.875rem; background-color: var(--surface-subtle); padding: 1px 4px; border-radius: var(--radius-sm); border: 1px solid var(--line); }
    .header-actions { display: flex; align-items: center; gap: 12px; }
    .books-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 310px), 1fr)); gap: 20px; align-items: start; }
    .book-card { display: flex; flex-direction: column; min-height: 380px; }
    .book-card-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid var(--line); gap: 8px; }
    .party-name { font-size: 1.125rem; margin-bottom: 2px; }
    .party-role { margin: 0; }
    .book-status, .book-error { padding: 24px 8px; text-align: center; }
    .error-msg { color: var(--status-declined); margin-bottom: 8px; }
    .accounts-container { display: flex; flex-direction: column; gap: 16px; flex: 1; }
    .accounts-section { display: flex; flex-direction: column; gap: 6px; }
    .section-micro-label { font-size: 0.6875rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--ink-faint); }
    .accounts-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
    .account-row { display: flex; justify-content: space-between; align-items: center; padding: 6px 8px; background-color: var(--surface-subtle); border-radius: var(--radius-sm); font-size: 0.8125rem; gap: 8px; }
    .account-name { color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 65%; }
    .account-balance { font-weight: 600; color: var(--ink); white-space: nowrap; }
    .balance-credit { color: var(--ink-muted); }
    .accounts-group { border: 1px solid var(--line); border-radius: var(--radius-sm); overflow: hidden; }
    .group-summary { display: flex; justify-content: space-between; align-items: center; padding: 8px 10px; background-color: var(--surface-subtle); cursor: pointer; font-size: 0.8125rem; font-weight: 500; user-select: none; }
    .group-summary:hover { background-color: var(--line); }
    .group-title { color: var(--accent); }
    .group-total { font-weight: 600; }
    .group-list { padding: 8px; display: flex; flex-direction: column; gap: 4px; background-color: var(--surface); }
    .account-sub-row { background-color: transparent; border-bottom: 1px solid var(--line); border-radius: 0; padding: 4px 0; }
    .account-sub-row:last-child { border-bottom: none; }
    .book-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 20px; padding-top: 12px; border-top: 2px solid var(--line-strong); }
    .total-col { display: flex; align-items: baseline; gap: 8px; }
    .total-label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--ink-faint); }
    .total-amount { font-size: 1.125rem; font-weight: 600; color: var(--ink); }
    .balance-check { display: inline-flex; align-items: center; gap: 6px; font-size: 0.8125rem; font-weight: 600; color: var(--status-approved); background-color: var(--status-approved-bg); padding: 3px 8px; border-radius: 999px; }
    .check-symbol { font-weight: 700; }
    .unbalanced-flag { display: inline-flex; align-items: center; font-size: 0.8125rem; font-weight: 600; color: var(--status-declined); background-color: var(--status-declined-bg); padding: 3px 8px; border-radius: 999px; }
  `,
})
export class Books implements OnInit, OnDestroy {
  private readonly api = inject(Api);

  protected readonly parties = BOOK_PARTIES;
  protected readonly partyStates = signal<Record<string, PartyBookState>>({
    kestrel: { meta: BOOK_PARTIES[0], book: null, loading: true, error: '' },
    railnet: { meta: BOOK_PARTIES[1], book: null, loading: true, error: '' },
    'railpay-acquiring': { meta: BOOK_PARTIES[2], book: null, loading: true, error: '' },
  });

  protected readonly refreshing = signal<boolean>(false);
  private timer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.refreshAll();
    this.startPolling();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (typeof document === 'undefined') {
      return;
    }
    if (document.visibilityState === 'visible') {
      this.refreshAll();
      if (!this.timer) {
        this.startPolling();
      }
    } else {
      this.stopPolling();
    }
  }

  protected refreshAll(): void {
    this.refreshing.set(true);
    const promises = this.parties.map((p) => this.loadParty(p.id));
    Promise.allSettled(promises).finally(() => {
      this.refreshing.set(false);
    });
  }

  protected async loadParty(partyId: string): Promise<void> {
    this.updatePartyState(partyId, { loading: true, error: '' });
    try {
      const book = await this.api.getPartyBook(partyId);
      this.updatePartyState(partyId, { book, loading: false, error: '' });
    } catch (err: unknown) {
      this.updatePartyState(partyId, { loading: false, error: 'Failed to load accounts.' });
    }
  }

  private updatePartyState(partyId: string, partial: Partial<PartyBookState>): void {
    this.partyStates.update((current) => {
      const existing = current[partyId] ?? {
        meta: this.parties.find((p) => p.id === partyId) ?? { id: partyId, name: partyId, role: '' },
        book: null,
        loading: false,
        error: '',
      };
      return {
        ...current,
        [partyId]: { ...existing, ...partial },
      };
    });
  }

  private startPolling(): void {
    if (typeof window !== 'undefined') {
      this.stopPolling();
      this.timer = setInterval(() => {
        if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
          this.refreshAll();
        }
      }, 10000);
    }
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  protected getSections(accounts: Record<string, number>): BookAccountSections {
    return groupBookAccounts(accounts);
  }

  protected formatMoney(cents: number): string {
    return formatCents(cents);
  }
}
