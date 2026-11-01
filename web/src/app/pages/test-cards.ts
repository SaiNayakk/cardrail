import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { groupCardNumber } from '../core/cards';
import { Loader } from '../core/loader';
import { TestCard } from '../core/types';
import { CopyButton } from '../ui/copy-button';

@Component({
  selector: 'app-test-cards',
  imports: [RouterLink, CopyButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="page-header">
        <div>
          <h1>Simulated test cards</h1>
          <p class="muted">
            Use these cards in the demo shop to simulate different network scenarios and bank responses.
          </p>
        </div>
        <a routerLink="/shop" class="btn btn-secondary btn-sm">Go to demo shop</a>
      </div>

      @if (cardsLoader.loading()) {
        <div class="card" role="status">
          <p class="muted">Loading test cards…</p>
        </div>
      } @else if (cardsLoader.error(); as err) {
        <div class="card" role="alert">
          <p class="error-msg">{{ err }}</p>
          <button type="button" class="btn btn-secondary btn-sm" (click)="cardsLoader.reload()">
            Retry loading
          </button>
        </div>
      } @else if (cardsLoader.value(); as cards) {
        @if (cards.length === 0) {
          <div class="card" role="status">
            <p class="muted">No test cards are currently configured in this environment.</p>
          </div>
        } @else {
          <div class="table-wrap">
            <table aria-label="Available test payment cards">
              <thead>
                <tr>
                  <th>Card number</th>
                  <th>Expiry</th>
                  <th>CVV</th>
                  <th>Type</th>
                  <th>Outcome</th>
                </tr>
              </thead>
              <tbody>
                @for (card of cards; track card.number) {
                  <tr>
                    <td>
                      <div class="cell-copy-row">
                        <span class="mono card-number">{{ formatNumber(card.number) }}</span>
                        <app-copy-button
                          [textToCopy]="card.number"
                          fieldName="card number"
                        />
                      </div>
                    </td>
                    <td>
                      <div class="cell-copy-row">
                        <span class="mono">{{ card.expiry }}</span>
                        <app-copy-button
                          [textToCopy]="card.expiry"
                          fieldName="expiry date"
                        />
                      </div>
                    </td>
                    <td>
                      <div class="cell-copy-row">
                        <span class="mono">{{ card.cvv2 }}</span>
                        <app-copy-button
                          [textToCopy]="card.cvv2"
                          fieldName="security code"
                        />
                      </div>
                    </td>
                    <td>
                      <span class="badge">{{ card.kind }}</span>
                    </td>
                    <td class="outcome-cell">
                      {{ card.outcome }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    </main>
  `,
  styles: `
    .page-header {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      margin: 16px 0 24px;
      flex-wrap: wrap;
      gap: 12px;
    }

    .cell-copy-row {
      display: inline-flex;
      align-items: center;
      gap: 8px;
    }

    .card-number {
      font-weight: 500;
      letter-spacing: 0.04em;
    }

    .outcome-cell {
      color: var(--ink);
      font-weight: 500;
    }

    .error-msg {
      color: var(--status-declined);
      font-weight: 500;
      margin-bottom: 12px;
    }
  `,
})
export class TestCards {
  private readonly api = inject(Api);
  protected readonly cardsLoader = new Loader<TestCard[]>(() => this.api.getTestCards());

  protected formatNumber(num: string): string {
    return groupCardNumber(num);
  }
}
