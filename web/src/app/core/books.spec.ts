import { describe, it, expect } from 'vitest';
import { groupBookAccounts } from './books';

describe('groupBookAccounts', () => {
  it('correctly sorts and groups accounts into their required sections', () => {
    const rawAccounts = {
      'cardholder:user-1': -2500,
      'income:interchange': -432,
      'bank:reserves': 1200000,
      'merchant:sprout': -9750,
      'due:railnet': 0,
      'expense:scheme_fees': 15,
      'cardholder:user-2': -1500,
      'bank:settlement': 50000,
    };

    const grouped = groupBookAccounts(rawAccounts);

    // 1. Bank and due accounts first, sorted alphabetically
    expect(grouped.bankAndDue).toEqual([
      { name: 'bank:reserves', balance: 1200000 },
      { name: 'bank:settlement', balance: 50000 },
      { name: 'due:railnet', balance: 0 },
    ]);

    // 2. Income and expense accounts next, sorted alphabetically
    expect(grouped.incomeAndExpense).toEqual([
      { name: 'expense:scheme_fees', balance: 15 },
      { name: 'income:interchange', balance: -432 },
    ]);

    // 3. Cardholder group collapsed under total
    expect(grouped.cardholderGroup.count).toBe(2);
    expect(grouped.cardholderGroup.total).toBe(-4000);
    expect(grouped.cardholderGroup.label).toBe('2 cardholder accounts');
    expect(grouped.cardholderGroup.accounts).toEqual([
      { name: 'cardholder:user-1', balance: -2500 },
      { name: 'cardholder:user-2', balance: -1500 },
    ]);

    // 4. Merchant group collapsed under total
    expect(grouped.merchantGroup.count).toBe(1);
    expect(grouped.merchantGroup.total).toBe(-9750);
    expect(grouped.merchantGroup.label).toBe('1 merchant account');
    expect(grouped.merchantGroup.accounts).toEqual([
      { name: 'merchant:sprout', balance: -9750 },
    ]);

    // 5. No uncategorised other accounts
    expect(grouped.other).toHaveLength(0);
  });

  it('handles empty accounts input gracefully', () => {
    const grouped = groupBookAccounts({});
    expect(grouped.bankAndDue).toHaveLength(0);
    expect(grouped.incomeAndExpense).toHaveLength(0);
    expect(grouped.cardholderGroup.count).toBe(0);
    expect(grouped.cardholderGroup.total).toBe(0);
    expect(grouped.cardholderGroup.label).toBe('0 cardholder accounts');
    expect(grouped.merchantGroup.count).toBe(0);
    expect(grouped.merchantGroup.total).toBe(0);
    expect(grouped.merchantGroup.label).toBe('0 merchant accounts');
    expect(grouped.other).toHaveLength(0);

    const fromNull = groupBookAccounts(null);
    expect(fromNull.bankAndDue).toHaveLength(0);
  });

  it('places non-standard accounts into the other section', () => {
    const raw = {
      'custom:clearing_pool': 500,
      'bank:operating': 1000,
    };
    const grouped = groupBookAccounts(raw);
    expect(grouped.bankAndDue).toHaveLength(1);
    expect(grouped.other).toEqual([{ name: 'custom:clearing_pool', balance: 500 }]);
  });
});
