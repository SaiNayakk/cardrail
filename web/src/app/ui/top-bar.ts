import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-top-bar',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="header">
      <div class="header-inner">
        <a routerLink="/" class="brand" aria-label="cardrail home">
          <svg class="logo" viewBox="0 0 32 32" fill="none" aria-hidden="true">
            <rect width="32" height="32" rx="7" fill="currentColor" />
            <rect x="6" y="8" width="20" height="16" rx="3" stroke="var(--bg)" stroke-width="2" />
            <line x1="6" y1="13" x2="26" y2="13" stroke="var(--bg)" stroke-width="2" />
            <circle cx="11" cy="18" r="1.5" fill="var(--bg)" />
            <circle cx="16" cy="18" r="1.5" fill="var(--bg)" />
            <circle cx="21" cy="18" r="1.5" fill="var(--bg)" />
          </svg>
          <span class="brand-text">cardrail</span>
        </a>

        <nav class="nav" aria-label="Main navigation">
          <a
            routerLink="/shop"
            routerLinkActive="active"
            class="nav-link"
          >Shop</a>
          <a
            routerLink="/how-it-works"
            routerLinkActive="active"
            class="nav-link"
          >How it works</a>
          <a
            routerLink="/test-cards"
            routerLinkActive="active"
            class="nav-link"
          >Test cards</a>
          <a
            routerLink="/traces"
            routerLinkActive="active"
            class="nav-link"
          >Traces</a>
        </nav>
      </div>
    </header>
  `,
  styles: `
    .header {
      background-color: var(--surface);
      border-bottom: 1px solid var(--line);
      position: sticky;
      top: 0;
      z-index: 20;
    }

    .header-inner {
      max-width: 1080px;
      margin: 0 auto;
      padding: 0 16px;
      height: 56px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }

    .brand {
      display: inline-flex;
      align-items: center;
      gap: 10px;
      text-decoration: none;
      color: var(--ink);
      font-weight: 600;
      font-size: 1.125rem;
      letter-spacing: -0.02em;
    }

    .brand:hover {
      color: var(--ink);
    }

    .logo {
      width: 26px;
      height: 26px;
      flex-shrink: 0;
      color: var(--ink);
    }

    .brand-text {
      line-height: 1;
    }

    .nav {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .nav-link {
      padding: 8px 12px;
      border-radius: var(--radius-sm);
      color: var(--ink-muted);
      text-decoration: none;
      font-size: 0.875rem;
      font-weight: 500;
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      transition: color var(--duration-fast) var(--ease-out),
                  background-color var(--duration-fast) var(--ease-out);
    }

    .nav-link:hover {
      color: var(--ink);
      background-color: var(--surface-subtle);
    }

    .nav-link.active {
      color: var(--accent);
      background-color: var(--accent-soft);
      font-weight: 600;
    }
  `,
})
export class TopBar {}
