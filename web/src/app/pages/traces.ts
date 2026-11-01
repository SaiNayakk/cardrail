import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { Loader } from '../core/loader';
import { formatEventTime } from '../core/trace-layout';
import { TraceSummary } from '../core/types';

@Component({
  selector: 'app-traces',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="page-header">
        <div>
          <h1>Recent payment traces</h1>
          <p class="muted">
            Trace logs recording real-time message exchange across the five payment ecosystem parties.
          </p>
        </div>
        <div class="header-actions">
          <button
            type="button"
            class="btn btn-secondary btn-sm"
            [disabled]="tracesLoader.loading()"
            (click)="tracesLoader.reload()"
          >
            {{ tracesLoader.loading() ? 'Refreshing…' : 'Refresh' }}
          </button>
          <a routerLink="/shop" class="btn btn-sm">New payment in shop</a>
        </div>
      </div>

      @if (tracesLoader.loading() && !tracesLoader.value()) {
        <div class="card" role="status">
          <p class="muted">Loading recent payment traces…</p>
        </div>
      } @else if (tracesLoader.error(); as err) {
        <div class="card" role="alert">
          <p class="error-msg">{{ err }}</p>
          <button type="button" class="btn btn-secondary btn-sm" (click)="tracesLoader.reload()">
            Retry loading
          </button>
        </div>
      } @else if (tracesLoader.value(); as traces) {
        @if (traces.length === 0) {
          <div class="card empty-card" role="status">
            <h2>No traces recorded yet</h2>
            <p class="muted">
              Make a checkout attempt with a test card in the demo shop to generate your first live message trace.
            </p>
            <a routerLink="/shop" class="btn">Go to demo shop</a>
          </div>
        } @else {
          <div class="traces-list" role="feed" aria-label="Recent payment traces">
            @for (item of traces; track item.traceKey) {
              <article class="trace-card">
                <div class="trace-info">
                  <div class="ref-heading-row">
                    <a [routerLink]="['/trace', item.traceKey]" class="trace-ref-link mono">
                      {{ item.traceKey }}
                    </a>
                    <span class="badge">{{ item.events }} {{ item.events === 1 ? 'message' : 'messages' }}</span>
                  </div>
                  <p class="trace-summary">{{ item.firstSummary }}</p>
                  <span class="trace-time mono small faint">
                    Started: {{ formatTime(item.startedAt) }}
                  </span>
                </div>

                <div class="trace-cta">
                  <a [routerLink]="['/trace', item.traceKey]" class="btn btn-secondary btn-sm">
                    View trace &rarr;
                  </a>
                </div>
              </article>
            }
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

    .header-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .empty-card {
      text-align: center;
      padding: 48px 16px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
    }

    .empty-card h2 {
      margin-bottom: 0;
    }

    .empty-card p {
      max-width: 480px;
    }

    .traces-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .trace-card {
      background-color: var(--surface);
      border: 1px solid var(--line);
      border-radius: var(--radius);
      padding: 16px 20px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      transition: border-color var(--duration-fast) var(--ease-out);
    }

    .trace-card:hover {
      border-color: var(--line-strong);
    }

    .trace-info {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .ref-heading-row {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .trace-ref-link {
      font-size: 1.05rem;
      font-weight: 600;
      color: var(--accent);
      text-decoration: none;
    }

    .trace-ref-link:hover {
      text-decoration: underline;
    }

    .trace-summary {
      color: var(--ink);
      font-weight: 500;
      margin: 0;
      font-size: 0.9375rem;
    }

    .trace-time {
      font-size: 0.75rem;
    }

    .trace-cta {
      flex-shrink: 0;
    }

    .error-msg {
      color: var(--status-declined);
      font-weight: 500;
      margin-bottom: 12px;
    }

    @media (max-width: 600px) {
      .trace-card {
        flex-direction: column;
        align-items: flex-start;
      }

      .trace-cta {
        width: 100%;
      }

      .trace-cta .btn {
        width: 100%;
      }
    }
  `,
})
export class Traces {
  private readonly api = inject(Api);
  protected readonly tracesLoader = new Loader<TraceSummary[]>(() => this.api.getRecentTraces());

  protected formatTime(isoString: string): string {
    return formatEventTime(isoString);
  }
}
