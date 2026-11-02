// PERF-01: card payments through every party, under steady load.
//
// Hypothesis: at a steady 5 checkouts a second, each one tokenising a card in Railpay's vault and then paying the
// demo shop (gateway, acquirer, Railnet and Kestrel Bank over ISO 8583, capture, trace reports), p95 latency stays
// under 1 s and fewer than 1% fail, within each host's memory limit. The first 30 s ramp up and are reported
// separately, to the same bar.
//
// Five a second is about 18,000 payments an hour, far above what a phone-hosted demo will see. Capacity itself is
// measured and recorded in docs/testing.md, not gated.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8280';

export const options = {
  scenarios: {
    warmup: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      stages: [{ target: 5, duration: '30s' }],
      preAllocatedVUs: 10,
      maxVUs: 40,
    },
    steady: {
      executor: 'constant-arrival-rate',
      startTime: '30s',
      rate: 5,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 10,
      maxVUs: 40,
    },
  },
  thresholds: {
    'http_req_duration{scenario:steady,name:checkout}': ['p(95)<1000', 'p(99)<2000'],
    'http_req_duration{scenario:steady,name:tokenise}': ['p(95)<300'],
    'http_req_failed{scenario:steady}': ['rate<0.01'],
    'checks{scenario:steady}': ['rate>0.99'],
    'http_req_duration{scenario:warmup,name:checkout}': ['p(95)<1000'],
    'http_req_failed{scenario:warmup}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export function setup() {
  const key = http.get(`${BASE}/api/shop/v1/config`).json('publishableKey');
  const card = http.get(`${BASE}/api/test-cards`).json().find((c) => c.outcome.startsWith('Debit card with $5,000'));
  if (!key || !card) throw new Error('the demo shop or its test card is missing');
  const [month, year] = card.expiry.split('/');
  return { key, card: { number: card.number, expMonth: +month, expYear: +year, cvc: card.cvv2 } };
}

function orderId() {
  return `perf-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

export default function (data) {
  const token = http.post(`${BASE}/hosted/tokens`, JSON.stringify({ publishableKey: data.key, number: data.card.number,
    expMonth: data.card.expMonth, expYear: data.card.expYear, cvc: data.card.cvc }), { ...JSON_HEADERS, tags: { name: 'tokenise' } });
  if (!check(token, { tokenised: (r) => r.status === 201 })) return;
  const paid = http.post(`${BASE}/api/shop/v1/checkout`, JSON.stringify({ product: 'sticker', quantity: 1,
    token: token.json('id'), orderId: orderId() }), { ...JSON_HEADERS, tags: { name: 'checkout' } });
  check(paid, { captured: (r) => r.status === 200 && r.json('status') === 'captured' });
}

export function handleSummary(data) {
  return {
    '/out/perf-01-k6-summary.json': JSON.stringify(data, null, 2),
    stdout: `PERF-01 checkout p95 ${Math.round(data.metrics['http_req_duration{scenario:steady,name:checkout}']?.values['p(95)'] ?? -1)} ms, `
      + `failed ${((data.metrics['http_req_failed{scenario:steady}']?.values.rate ?? 1) * 100).toFixed(2)}%\n`,
  };
}
