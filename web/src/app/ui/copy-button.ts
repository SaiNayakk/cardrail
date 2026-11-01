import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

@Component({
  selector: 'app-copy-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      class="btn-copy"
      [class.copied]="copied()"
      (click)="copy()"
      [attr.aria-label]="ariaLabel()"
      [title]="copied() ? 'Copied to clipboard' : 'Copy to clipboard'"
    >
      @if (copied()) {
        <svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <polyline points="3 8.5 6.5 12 13 4" />
        </svg>
        <span class="text">Copied</span>
      } @else {
        <svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <rect x="5" y="5" width="8" height="8" rx="1.5" />
          <path d="M11 5V3.5A1.5 1.5 0 0 0 9.5 2h-6A1.5 1.5 0 0 0 2 3.5v6A1.5 1.5 0 0 0 3.5 11H5" />
        </svg>
        <span class="text">{{ label() }}</span>
      }
    </button>
  `,
  styles: `
    .btn-copy {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 8px;
      min-height: 28px;
      font-size: 0.75rem;
      font-weight: 500;
      color: var(--ink-muted);
      background-color: var(--surface-subtle);
      border: 1px solid var(--line);
      border-radius: var(--radius-sm);
      cursor: pointer;
      transition: background-color var(--duration-fast) var(--ease-out),
                  border-color var(--duration-fast) var(--ease-out),
                  color var(--duration-fast) var(--ease-out);
    }

    .btn-copy:hover {
      background-color: var(--line);
      color: var(--ink);
    }

    .btn-copy.copied {
      background-color: var(--status-approved-bg);
      border-color: var(--status-approved);
      color: var(--status-approved);
    }

    .icon {
      width: 13px;
      height: 13px;
      flex-shrink: 0;
    }

    .text {
      line-height: 1;
    }
  `,
})
export class CopyButton {
  readonly textToCopy = input.required<string>();
  readonly label = input<string>('Copy');
  readonly fieldName = input<string>('value');

  protected readonly copied = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  protected ariaLabel(): string {
    return this.copied() ? `Copied ${this.fieldName()}` : `Copy ${this.fieldName()}`;
  }

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.textToCopy());
      this.copied.set(true);

      if (this.timer) {
        clearTimeout(this.timer);
      }
      this.timer = setTimeout(() => {
        this.copied.set(false);
        this.timer = null;
      }, 1500);
    } catch {
      // Fallback if clipboard API is denied
    }
  }
}
