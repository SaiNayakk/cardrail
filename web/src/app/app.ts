import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TopBar } from './ui/top-bar';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, TopBar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="app-layout">
      <app-top-bar />
      <div class="content-area">
        <router-outlet />
      </div>
      <footer class="footer">
        <div class="footer-inner">
          <p class="muted small">
            cardrail &middot; five-party card payment ecosystem simulator
          </p>
        </div>
      </footer>
    </div>
  `,
  styles: `
    .app-layout {
      min-height: 100dvh;
      display: flex;
      flex-direction: column;
    }

    .content-area {
      flex: 1;
    }

    .footer {
      border-top: 1px solid var(--line);
      background-color: var(--surface);
      padding: 16px 0;
      margin-top: 48px;
    }

    .footer-inner {
      max-width: 1080px;
      margin: 0 auto;
      padding: 0 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .footer-inner p {
      margin: 0;
    }
  `,
})
export class App {}
