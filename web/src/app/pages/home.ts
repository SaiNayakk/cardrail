import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PartyDiagram } from '../ui/party-diagram';

@Component({
  selector: 'app-home',
  imports: [RouterLink, PartyDiagram],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="container">
      <section class="hero-section">
        <h1>Full-ecosystem card payments, live and inspected.</h1>
        <p class="lede">
          Cardrail is an interactive demonstration of a five-party card payment network. When you make a purchase in the demo shop, you can trace the resulting API and ISO 8583 messages as they travel across the merchant, gateway, acquirer, network, and issuing bank in real time.
        </p>
      </section>

      <app-party-diagram />

      <section class="actions-grid">
        <div class="card action-card">
          <h2>Demo shop</h2>
          <p class="muted small">
            Purchase demo goods with test cards. Card data is tokenised securely by the hosted gateway before checkout.
          </p>
          <a routerLink="/shop" class="btn">Go to shop</a>
        </div>

        <div class="card action-card">
          <h2>Test cards</h2>
          <p class="muted small">
            View cards configured for different outcomes: instant approvals, insufficient funds, and network declines.
          </p>
          <a routerLink="/test-cards" class="btn btn-secondary">View test cards</a>
        </div>

        <div class="card action-card">
          <h2>Recent traces</h2>
          <p class="muted small">
            Inspect previous payment journeys and review raw ISO 8583 data elements exchanged between parties.
          </p>
          <a routerLink="/traces" class="btn btn-secondary">Inspect traces</a>
        </div>
      </section>
    </main>
  `,
  styles: `
    .hero-section {
      max-width: 720px;
      margin: 16px 0 24px;
    }

    .lede {
      font-size: 1.0625rem;
      line-height: 1.6;
      color: var(--ink-muted);
      margin-top: 12px;
    }

    .actions-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
      gap: 16px;
      margin-top: 24px;
    }

    .action-card {
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      gap: 16px;
    }

    .action-card h2 {
      margin-bottom: 4px;
    }

    .action-card .btn {
      align-self: flex-start;
    }
  `,
})
export class Home {}
