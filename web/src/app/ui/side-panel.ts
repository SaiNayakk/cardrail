import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  ViewChild,
  computed,
  input,
  output,
} from '@angular/core';
import { formatIsoField, FormattedIsoField } from '../core/iso8583';
import { PARTY_NAMES, TraceEvent } from '../core/types';

interface NoteDetailField {
  key: string;
  value: string;
}

@Component({
  selector: 'app-side-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (isOpen() && event(); as ev) {
      <div class="backdrop" (click)="close.emit()" aria-hidden="true"></div>

      <aside
        #panelElement
        class="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="panel-title"
      >
        <div class="panel-header">
          <div>
            <span class="panel-tag">{{ ev.kind === 'iso8583' ? 'ISO 8583 Message' : 'Event Note' }}</span>
            <h2 id="panel-title" class="panel-title">
              {{ ev.kind === 'iso8583' && ev.mti ? ev.mti + ' ' + ev.summary : ev.summary }}
            </h2>
          </div>
          <button
            #closeBtn
            type="button"
            class="btn-close"
            (click)="close.emit()"
            aria-label="Close message details panel"
            title="Close (Esc)"
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M3 3l10 10M13 3L3 13" />
            </svg>
          </button>
        </div>

        <div class="panel-body">
          <div class="meta-card">
            <div class="meta-row">
              <span class="meta-label">Routing</span>
              <span class="meta-value">{{ partyName(ev.from) }} &rarr; {{ partyName(ev.to) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">Reporter</span>
              <span class="meta-value">{{ partyName(ev.reporter) }}</span>
            </div>
            @if (ev.mti) {
              <div class="meta-row">
                <span class="meta-label">MTI</span>
                <span class="meta-value mono">{{ ev.mti }}</span>
              </div>
            }
            <div class="meta-row">
              <span class="meta-label">Timestamp</span>
              <span class="meta-value mono">{{ ev.at }}</span>
            </div>
          </div>

          <div class="fields-section">
            <h3 class="section-heading">
              {{ ev.kind === 'iso8583' ? 'Data Elements' : 'Details' }}
            </h3>

            @if (ev.kind === 'iso8583') {
              @if (isoFields().length > 0) {
                <div class="table-wrap">
                  <table class="fields-table">
                    <thead>
                      <tr>
                        <th style="width: 50px;">DE</th>
                        <th>Field Name</th>
                        <th>Value</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (item of isoFields(); track item.field) {
                        <tr>
                          <td class="mono num">{{ item.field }}</td>
                          <td class="field-name">{{ item.name }}</td>
                          <td class="mono field-val">{{ item.formattedValue }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <p class="empty-note">No ISO 8583 data elements recorded for this message.</p>
              }
            } @else {
              @if (noteDetails().length > 0) {
                <div class="table-wrap">
                  <table class="fields-table">
                    <thead>
                      <tr>
                        <th>Property</th>
                        <th>Value</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (item of noteDetails(); track item.key) {
                        <tr>
                          <td class="field-name">{{ item.key }}</td>
                          <td class="mono field-val">{{ item.value }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <p class="empty-note">No additional parameters recorded for this note.</p>
              }
            }
          </div>
        </div>
      </aside>
    }
  `,
  styles: `
    .backdrop {
      position: fixed;
      inset: 0;
      background-color: rgba(15, 23, 42, 0.4);
      z-index: 40;
      animation: fadeIn var(--duration-fast) var(--ease-out);
    }

    .panel {
      position: fixed;
      top: 0;
      right: 0;
      bottom: 0;
      width: min(520px, 92vw);
      background-color: var(--surface);
      border-left: 1px solid var(--line-strong);
      box-shadow: var(--shadow-panel);
      z-index: 50;
      display: flex;
      flex-direction: column;
      animation: slideIn var(--duration-normal) var(--ease-out);
    }

    @keyframes fadeIn {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    @keyframes slideIn {
      from { transform: translateX(100%); }
      to { transform: translateX(0); }
    }

    .panel-header {
      padding: 20px;
      border-bottom: 1px solid var(--line);
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      background-color: var(--surface-subtle);
    }

    .panel-tag {
      font-size: 0.6875rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--accent);
      display: block;
      margin-bottom: 4px;
    }

    .panel-title {
      font-size: 1.125rem;
      font-weight: 600;
      color: var(--ink);
      margin: 0;
      word-break: break-word;
    }

    .btn-close {
      background: transparent;
      border: 1px solid var(--line);
      border-radius: var(--radius-sm);
      color: var(--ink-muted);
      cursor: pointer;
      width: 36px;
      height: 36px;
      padding: 0;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      transition: background-color var(--duration-fast) var(--ease-out),
                  color var(--duration-fast) var(--ease-out);
    }

    .btn-close:hover {
      background-color: var(--line);
      color: var(--ink);
    }

    .btn-close svg {
      width: 14px;
      height: 14px;
    }

    .panel-body {
      padding: 20px;
      overflow-y: auto;
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 20px;
      -webkit-overflow-scrolling: touch;
    }

    .meta-card {
      background-color: var(--surface-subtle);
      border: 1px solid var(--line);
      border-radius: var(--radius);
      padding: 12px 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .meta-row {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      font-size: 0.8125rem;
    }

    .meta-label {
      color: var(--ink-faint);
      font-weight: 500;
      flex-shrink: 0;
    }

    .meta-value {
      color: var(--ink);
      font-weight: 500;
      text-align: right;
      word-break: break-all;
    }

    .section-heading {
      font-size: 0.875rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--ink-faint);
      margin-bottom: 8px;
    }

    .fields-table {
      font-size: 0.8125rem;
    }

    .fields-table th {
      padding: 8px 10px;
      font-size: 0.6875rem;
    }

    .fields-table td {
      padding: 8px 10px;
    }

    .field-name {
      color: var(--ink);
      font-weight: 500;
    }

    .field-val {
      color: var(--ink-muted);
      word-break: break-all;
    }

    .empty-note {
      font-size: 0.8125rem;
      color: var(--ink-muted);
      font-style: italic;
      padding: 8px 0;
    }
  `,
})
export class SidePanel {
  readonly event = input<TraceEvent | null>(null);
  readonly isOpen = input<boolean>(false);
  readonly close = output<void>();

  @ViewChild('closeBtn') private closeBtnRef?: ElementRef<HTMLButtonElement>;

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.isOpen()) {
      this.close.emit();
    }
  }

  protected readonly isoFields = computed<FormattedIsoField[]>(() => {
    const ev = this.event();
    if (!ev || ev.kind !== 'iso8583' || !ev.details) {
      return [];
    }
    const entries = Object.entries(ev.details);
    entries.sort(([a], [b]) => {
      const numA = Number.parseInt(a, 10);
      const numB = Number.parseInt(b, 10);
      if (!Number.isNaN(numA) && !Number.isNaN(numB)) {
        return numA - numB;
      }
      return a.localeCompare(b);
    });
    return entries.map(([fieldNum, val]) => formatIsoField(fieldNum, val));
  });

  protected readonly noteDetails = computed<NoteDetailField[]>(() => {
    const ev = this.event();
    if (!ev || ev.kind === 'iso8583' || !ev.details) {
      return [];
    }
    return Object.entries(ev.details).map(([key, value]) => ({
      key,
      value: String(value),
    }));
  });

  protected partyName(partyId: string): string {
    return PARTY_NAMES[partyId] ?? partyId;
  }
}
