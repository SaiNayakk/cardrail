import { Payout } from './types';

export interface PayoutSummary {
  sales: number;
  refunds: number;
  fees: number;
  disputes: number;
  netPaid: number;
  count: number;
}

/** Computes financial aggregate totals across a list of shop payouts. */
export function computePayoutSummary(payouts: Payout[] | null | undefined): PayoutSummary {
  if (!payouts || !Array.isArray(payouts) || payouts.length === 0) {
    return {
      sales: 0,
      refunds: 0,
      fees: 0,
      disputes: 0,
      netPaid: 0,
      count: 0,
    };
  }

  let sales = 0;
  let refunds = 0;
  let fees = 0;
  let disputes = 0;
  let netPaid = 0;

  for (const p of payouts) {
    sales += typeof p.sales === 'number' && !Number.isNaN(p.sales) ? p.sales : 0;
    refunds += typeof p.refunds === 'number' && !Number.isNaN(p.refunds) ? p.refunds : 0;
    fees += typeof p.fees === 'number' && !Number.isNaN(p.fees) ? p.fees : 0;
    disputes += typeof p.disputes === 'number' && !Number.isNaN(p.disputes) ? p.disputes : 0;
    netPaid += typeof p.amount === 'number' && !Number.isNaN(p.amount) ? p.amount : 0;
  }

  return {
    sales,
    refunds,
    fees,
    disputes,
    netPaid,
    count: payouts.length,
  };
}
