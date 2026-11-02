import { describe, it, expect } from 'vitest';
import { computePayoutSummary } from './payouts';
import { Payout } from './types';

describe('computePayoutSummary', () => {
  it('correctly sums sales, refunds, fees, disputes, and net paid amounts', () => {
    const payouts: Payout[] = [
      {
        id: 'po_1',
        object: 'payout',
        amount: 9750,
        sales: 10000,
        refunds: 0,
        fees: 250,
        disputes: 0,
        createdAt: '2026-11-01T12:00:00Z',
      },
      {
        id: 'po_2',
        object: 'payout',
        amount: 14200,
        sales: 16000,
        refunds: 1000,
        fees: 400,
        disputes: 400,
        createdAt: '2026-11-02T12:00:00Z',
      },
    ];

    const summary = computePayoutSummary(payouts);

    expect(summary.sales).toBe(26000);
    expect(summary.refunds).toBe(1000);
    expect(summary.fees).toBe(650);
    expect(summary.disputes).toBe(400);
    expect(summary.netPaid).toBe(23950);
    expect(summary.count).toBe(2);
  });

  it('handles empty or null payout list safely returning zeroes', () => {
    const emptySummary = computePayoutSummary([]);
    expect(emptySummary).toEqual({
      sales: 0,
      refunds: 0,
      fees: 0,
      disputes: 0,
      netPaid: 0,
      count: 0,
    });

    const nullSummary = computePayoutSummary(null);
    expect(nullSummary).toEqual({
      sales: 0,
      refunds: 0,
      fees: 0,
      disputes: 0,
      netPaid: 0,
      count: 0,
    });
  });

  it('correctly computes totals for a single payout', () => {
    const single: Payout[] = [
      {
        id: 'po_single',
        object: 'payout',
        amount: 4875,
        sales: 5000,
        refunds: 0,
        fees: 125,
        disputes: 0,
        createdAt: '2026-11-02T10:00:00Z',
      },
    ];
    const summary = computePayoutSummary(single);
    expect(summary.sales).toBe(5000);
    expect(summary.fees).toBe(125);
    expect(summary.netPaid).toBe(4875);
    expect(summary.count).toBe(1);
  });
});
