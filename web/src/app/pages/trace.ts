import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { messageOf } from '../core/problem';
import { StreamState, TraceStream, mergeTraceEvents } from '../core/stream';
import { DEFAULT_LANES, layoutTraceArrows } from '../core/trace-layout';
import { TraceEvent } from '../core/types';
import { CopyButton } from '../ui/copy-button';
import { SidePanel } from '../ui/side-panel';

@Component({
  selector: 'app-trace',
  imports: [RouterLink, CopyButton, SidePanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <div class="trace-header">
        <div class="trace-title-group">
          <a routerLink="/traces" class="back-link">&larr; Recent traces</a>
          <div class="ref-row">
            <h1>Payment trace</h1>
            <div class="ref-chip">
              <span class="mono ref-text">{{ reference() }}</span>
              <app-copy-button [textToCopy]="reference()" fieldName="trace reference" />
            </div>
            <span
              class="badge status-badge"
              [class.badge-approved]="streamState() === 'connected'"
            >
              {{ streamState() === 'connected' ? 'Live stream' : streamState() === 'connecting' ? 'Connecting' : 'Stream closed' }}
            </span>
          </div>
          <p class="diagram-explanation muted">
            Live message sequence showing requests and responses passing between ecosystem parties for this payment.
          </p>
        </div>
      </div>

      <!-- Live screen-reader updates -->
      <div class="sr-only" aria-live="polite" aria-atomic="true">
        {{ latestArrivalNotice() }}
      </div>

      @if (loading() && events().length === 0) {
        <div class="card" role="status">
          <p class="muted">Connecting to trace stream…</p>
        </div>
      } @else if (loadError() && events().length === 0) {
        <div class="card" role="alert">
          <p class="error-msg">{{ loadError() }}</p>
          <p class="muted small">
            The trace may not have started yet. If you just checked out, events should begin shortly.
          </p>
          <button type="button" class="btn btn-secondary btn-sm" (click)="retryInitialLoad()">
            Check again
          </button>
        </div>
      } @else {
        <!-- Wide Screen Sequence Diagram -->
        <div class="diagram-wrapper">
          <div class="sequence-container">
            <!-- Header row of party lanes -->
            <div class="lanes-header" role="row">
              @for (lane of lanes; track lane.id) {
                <div class="lane-col-header">
                  <span class="lane-name">{{ lane.name }}</span>
                </div>
              }
            </div>

            <!-- Sequence timeline body -->
            <div class="timeline-body">
              <!-- Background vertical guidelines -->
              <div class="guidelines-layer" aria-hidden="true">
                @for (lane of lanes; track lane.id) {
                  <div class="guideline-col">
                    <div class="guideline-line"></div>
                  </div>
                }
              </div>

              <!-- Message arrows list -->
              <div class="arrows-layer">
                @if (arrowLayouts().length === 0) {
                  <div class="waiting-state">
                    <p class="muted small">Waiting for initial message events from parties…</p>
                  </div>
                }

                @for (arrow of arrowLayouts(); track arrow.event.id; let idx = $index) {
                  <div class="arrow-row" [style.animation-delay]="(idx * 30) + 'ms'">
                    <!-- Time tag -->
                    <span class="arrow-time mono">{{ arrow.timeFormatted }}</span>

                    <!-- Event Arrow Button (interactive) -->
                    <div class="arrow-track">
                      <button
                        type="button"
                        class="arrow-bar-btn"
                        [class.arrow-forward]="arrow.direction === 'forward'"
                        [class.arrow-backward]="arrow.direction === 'backward'"
                        [class.arrow-self]="arrow.direction === 'self'"
                        [class.arrow-stand-in]="arrow.isStandIn"
                        [style.left.%]="arrow.leftPercent"
                        [style.width.%]="arrow.widthPercent"
                        (click)="selectEvent(arrow.event)"
                        [attr.aria-label]="'Inspect message: ' + arrow.label"
                      >
                        @if (arrow.direction === 'self') {
                          <div class="lane-marker-container">
                            <span class="lane-marker-dot" aria-hidden="true"></span>
                            <span class="arrow-label-pill pill-self" [class.pill-stand-in]="arrow.isStandIn">
                              <span class="summary-text">{{ arrow.event.summary }}</span>
                            </span>
                          </div>
                        } @else {
                          <div class="arrow-shaft" [class.shaft-stand-in]="arrow.isStandIn">
                            @if (arrow.direction === 'backward') {
                              <svg class="arrowhead arrow-left" viewBox="0 0 10 10" aria-hidden="true">
                                <polygon points="10,0 0,5 10,10" fill="currentColor" />
                              </svg>
                            }

                            <span class="arrow-label-pill" [class.pill-stand-in]="arrow.isStandIn">
                              @if (arrow.event.kind === 'iso8583') {
                                <span class="mti-tag mono" [title]="arrow.mtiName || ''">{{ arrow.event.mti }}</span>
                              }
                              <span class="summary-text">{{ arrow.event.summary }}</span>
                            </span>

                            @if (arrow.direction === 'forward') {
                              <svg class="arrowhead arrow-right" viewBox="0 0 10 10" aria-hidden="true">
                                <polygon points="0,0 10,5 0,10" fill="currentColor" />
                              </svg>
                            }
                          </div>
                        }
                      </button>
                    </div>
                  </div>
                }
              </div>
            </div>
          </div>
          <div class="diagram-legend faint small">
            <span class="legend-badge" aria-hidden="true"></span>
            <span>Dashed: Railnet decided for the issuer (stand-in)</span>
          </div>
        </div>

        <!-- Narrow Screen Step-by-Step Fallback (shown below 768px) -->
        <div class="narrow-steps-list" aria-label="Sequential message events list">
          <h2 class="sr-only">Sequential message steps</h2>
          @for (arrow of arrowLayouts(); track arrow.event.id) {
            <button
              type="button"
              class="step-card"
              [class.step-stand-in]="arrow.isStandIn"
              (click)="selectEvent(arrow.event)"
            >
              <div class="step-card-header">
                <span class="mono small muted">{{ arrow.timeFormatted }}</span>
                <span class="badge">{{ arrow.event.kind === 'iso8583' ? 'ISO 8583' : 'Note' }}</span>
              </div>
              <div class="step-routing">
                @if (arrow.direction === 'self') {
                  <span class="party-tag">{{ arrow.fromName }}</span>
                  <span class="muted small">(internal)</span>
                } @else {
                  <span class="party-tag">{{ arrow.fromName }}</span>
                  <span class="routing-arrow">&rarr;</span>
                  <span class="party-tag">{{ arrow.toName }}</span>
                }
              </div>
              <div class="step-summary">
                @if (arrow.event.kind === 'iso8583' && arrow.event.mti) {
                  <span class="mti-tag mono" [title]="arrow.mtiName || ''">{{ arrow.event.mti }}</span>
                }
                <span class="step-desc">{{ arrow.event.summary }}</span>
              </div>
            </button>
          }
          <div class="diagram-legend faint small">
            <span class="legend-badge" aria-hidden="true"></span>
            <span>Dashed: Railnet decided for the issuer (stand-in)</span>
          </div>
        </div>
      }

      <!-- Side panel for ISO 8583 fields and notes -->
      <app-side-panel
        [isOpen]="panelOpen()"
        [event]="selectedEvent()"
        (close)="closePanel()"
      />
    </main>
  `,
  styles: `
    .trace-header {
      margin: 16px 0 24px;
    }

    .back-link {
      display: inline-block;
      font-size: 0.875rem;
      color: var(--ink-muted);
      text-decoration: none;
      margin-bottom: 8px;
    }

    .back-link:hover {
      color: var(--accent);
    }

    .ref-row {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .ref-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      background-color: var(--surface);
      border: 1px solid var(--line);
      border-radius: var(--radius-sm);
    }

    .ref-text {
      font-weight: 600;
      font-size: 0.9375rem;
      letter-spacing: 0.04em;
    }

    .diagram-explanation {
      font-size: 0.9375rem;
      margin-top: 8px;
      margin-bottom: 0;
    }

    /* Sequence Diagram Layout */
    .diagram-wrapper {
      background-color: var(--surface);
      border: 1px solid var(--line);
      border-radius: var(--radius-lg);
      padding: 20px 16px;
      overflow-x: auto;
      -webkit-overflow-scrolling: touch;
      margin-bottom: 24px;
    }

    .sequence-container {
      min-width: 720px;
      position: relative;
    }

    .lanes-header {
      display: flex;
      border-bottom: 1px solid var(--line-strong);
      padding-bottom: 12px;
      margin-bottom: 16px;
    }

    .lane-col-header {
      flex: 1 1 0;
      text-align: center;
      padding: 0 4px;
    }

    .lane-name {
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--ink);
      display: block;
    }

    .timeline-body {
      position: relative;
      min-height: 240px;
    }

    .guidelines-layer {
      position: absolute;
      inset: 0;
      display: flex;
      pointer-events: none;
    }

    .guideline-col {
      flex: 1 1 0;
      display: flex;
      justify-content: center;
    }

    .guideline-line {
      width: 1px;
      height: 100%;
      background: repeating-linear-gradient(
        to bottom,
        var(--line-strong) 0,
        var(--line-strong) 4px,
        transparent 4px,
        transparent 8px
      );
    }

    .arrows-layer {
      position: relative;
      z-index: 2;
      display: flex;
      flex-direction: column;
      gap: 20px;
      padding: 12px 0 24px;
    }

    .waiting-state {
      padding: 40px 16px;
      text-align: center;
    }

    .arrow-row {
      position: relative;
      display: flex;
      align-items: center;
      min-height: 38px;
      animation: arrowEnter var(--duration-normal) var(--ease-out) backwards;
    }

    @keyframes arrowEnter {
      from {
        opacity: 0;
        transform: translateY(6px);
      }
      to {
        opacity: 1;
        transform: translateY(0);
      }
    }

    .arrow-time {
      position: absolute;
      left: 0;
      font-size: 0.6875rem;
      color: var(--ink-faint);
      width: 80px;
    }

    .arrow-track {
      width: 100%;
      position: relative;
      height: 36px;
    }

    .arrow-bar-btn {
      position: absolute;
      top: 0;
      height: 100%;
      background: transparent;
      border: none;
      padding: 0;
      cursor: pointer;
      display: flex;
      align-items: center;
      font-family: inherit;
    }

    .arrow-shaft {
      width: 100%;
      height: 2px;
      background-color: var(--accent);
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background-color var(--duration-fast) var(--ease-out);
    }

    .lane-marker-container {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      position: relative;
    }

    .lane-marker-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      background-color: var(--accent);
      border: 2px solid var(--surface);
      box-shadow: 0 0 0 1px var(--line-strong);
      position: absolute;
      left: 50%;
      top: 50%;
      transform: translate(-50%, -50%);
      z-index: 2;
      transition: transform var(--duration-fast) var(--ease-out),
                  background-color var(--duration-fast) var(--ease-out);
    }

    .arrow-bar-btn:hover .lane-marker-dot,
    .arrow-bar-btn:focus-visible .lane-marker-dot {
      transform: translate(-50%, -50%) scale(1.3);
      background-color: var(--ink);
    }

    .pill-self {
      z-index: 3;
    }

    .arrow-bar-btn:hover .arrow-shaft,
    .arrow-bar-btn:focus-visible .arrow-shaft {
      background-color: var(--ink);
    }

    .arrowhead {
      width: 8px;
      height: 8px;
      position: absolute;
      top: 50%;
      transform: translateY(-50%);
      color: var(--accent);
      flex-shrink: 0;
    }

    .arrow-bar-btn:hover .arrowhead,
    .arrow-bar-btn:focus-visible .arrowhead {
      color: var(--ink);
    }

    .arrowhead.arrow-left {
      left: 0;
    }

    .arrowhead.arrow-right {
      right: 0;
    }

    .arrow-label-pill {
      position: relative;
      z-index: 3;
      background-color: var(--surface);
      border: 1px solid var(--line-strong);
      border-radius: var(--radius-sm);
      padding: 4px 10px;
      font-size: 0.75rem;
      font-weight: 500;
      color: var(--ink);
      white-space: nowrap;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      max-width: 90%;
      overflow: hidden;
      text-overflow: ellipsis;
      box-shadow: var(--shadow-sm);
      transition: border-color var(--duration-fast) var(--ease-out),
                  transform var(--duration-fast) var(--ease-out);
    }

    .arrow-bar-btn:hover .arrow-label-pill,
    .arrow-bar-btn:focus-visible .arrow-label-pill {
      border-color: var(--accent);
      transform: scale(1.02);
    }

    .mti-tag {
      font-weight: 600;
      color: var(--accent);
      background-color: var(--accent-soft);
      padding: 1px 4px;
      border-radius: 2px;
    }

    .summary-text {
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .pill-stand-in {
      border: 1.5px dashed var(--accent);
      background-color: var(--accent-soft);
    }

    .shaft-stand-in {
      background: repeating-linear-gradient(
        to right,
        var(--accent) 0,
        var(--accent) 4px,
        transparent 4px,
        transparent 8px
      );
    }

    .step-stand-in {
      border: 1.5px dashed var(--accent);
      background-color: var(--accent-soft);
    }

    .diagram-legend {
      margin-top: 16px;
      padding-top: 12px;
      border-top: 1px solid var(--line);
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .legend-badge {
      display: inline-block;
      width: 24px;
      height: 2px;
      border-top: 2px dashed var(--accent);
    }

    /* Narrow Screen Steps (Fallback on small displays) */
    .narrow-steps-list {
      display: none;
      flex-direction: column;
      gap: 12px;
      margin-bottom: 24px;
    }

    .step-card {
      background-color: var(--surface);
      border: 1px solid var(--line);
      border-radius: var(--radius);
      padding: 14px;
      text-align: left;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 8px;
      font-family: inherit;
      width: 100%;
      transition: border-color var(--duration-fast) var(--ease-out);
    }

    .step-card:hover {
      border-color: var(--line-strong);
    }

    .step-card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .step-routing {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 0.8125rem;
      font-weight: 500;
    }

    .party-tag {
      color: var(--ink);
    }

    .routing-arrow {
      color: var(--accent);
    }

    .step-summary {
      display: flex;
      align-items: baseline;
      gap: 6px;
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--ink);
    }

    @media (max-width: 768px) {
      .diagram-wrapper {
        display: none;
      }

      .narrow-steps-list {
        display: flex;
      }
    }
  `,
})
export class Trace implements OnInit, OnDestroy {
  readonly reference = input.required<string>();

  private readonly api = inject(Api);

  protected readonly lanes = DEFAULT_LANES;
  protected readonly events = signal<TraceEvent[]>([]);
  protected readonly loading = signal<boolean>(true);
  protected readonly loadError = signal<string>('');
  protected readonly streamState = signal<StreamState>('connecting');
  protected readonly selectedEvent = signal<TraceEvent | null>(null);
  protected readonly panelOpen = signal<boolean>(false);
  protected readonly latestArrivalNotice = signal<string>('');

  private stream: TraceStream | null = null;

  protected readonly arrowLayouts = computed(() => {
    return layoutTraceArrows(this.events(), this.lanes);
  });

  ngOnInit(): void {
    const ref = this.reference();
    if (!ref) {
      this.loadError.set('No trace reference provided.');
      this.loading.set(false);
      return;
    }

    // Initial snapshot load (fallback / immediate bootstrap)
    this.fetchInitialTrace(ref);

    // Initialize Server-Sent Events stream
    this.stream = new TraceStream(
      ref,
      (ev: TraceEvent) => {
        this.events.update((current) => mergeTraceEvents(current, ev));
        this.loading.set(false);
        this.latestArrivalNotice.set(`New message: ${ev.summary}`);
      },
      (st: StreamState) => {
        this.streamState.set(st);
      },
    );
  }

  ngOnDestroy(): void {
    if (this.stream) {
      this.stream.destroy();
      this.stream = null;
    }
  }

  protected retryInitialLoad(): void {
    const ref = this.reference();
    if (ref) {
      this.fetchInitialTrace(ref);
    }
  }

  private fetchInitialTrace(ref: string): void {
    this.loading.set(true);
    this.loadError.set('');

    this.api
      .getTrace(ref)
      .then((detail) => {
        if (detail && Array.isArray(detail.events)) {
          this.events.update((current) => mergeTraceEvents(current, detail.events));
        }
      })
      .catch((err: unknown) => {
        // 404 is normal before the first event arrives; let SSE stream handle incoming messages
        const msg = messageOf(err);
        if (!msg.includes('not found')) {
          this.loadError.set(msg);
        }
      })
      .finally(() => {
        this.loading.set(false);
      });
  }

  protected selectEvent(event: TraceEvent): void {
    this.selectedEvent.set(event);
    this.panelOpen.set(true);
  }

  protected closePanel(): void {
    this.panelOpen.set(false);
  }
}
