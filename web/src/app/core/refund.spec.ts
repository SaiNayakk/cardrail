import { describe, it, expect } from 'vitest';
import {
  parseRefundAmount,
  createRefundRequestId,
  remainingRefundableCents,
  canRefundPayment,
} from './refund';
import { Payment } from './types';

describe('refund amount parsing and validation', () => {
  it('parses valid dollar amounts to cents', () => {
    expect(parseRefundAmount('3')).toEqual({ valid: true, cents: 300 });
    expect(parseRefundAmount('3.00')).toEqual({ valid: true, cents: 300 });
    expect(parseRefundAmount('19.99')).toEqual({ valid: true, cents: 1999 });
    expect(parseRefundAmount('0.50')).toEqual({ valid: true, cents: 50 });
    expect(parseRefundAmount('0.05')).toEqual({ valid: true, cents: 5 });
    expect(parseRefundAmount('.75')).toEqual({ valid: true, cents: 75 });
  });

  it('handles optional dollar sign, spaces, and commas', () => {
    expect(parseRefundAmount('$12.50')).toEqual({ valid: true, cents: 1250 });
    expect(parseRefundAmount('  $ 5.25  ')).toEqual({ valid: true, cents: 525 });
    expect(parseRefundAmount('$1,234.56')).toEqual({ valid: true, cents: 123456 });
  });

  it('treats empty input as omitted for all that is left by default', () => {
    expect(parseRefundAmount('')).toEqual({ valid: true, cents: undefined });
    expect(parseRefundAmount('   ')).toEqual({ valid: true, cents: undefined });
    expect(parseRefundAmount(null)).toEqual({ valid: true, cents: undefined });
    expect(parseRefundAmount(undefined)).toEqual({ valid: true, cents: undefined });
  });

  it('rejects empty input when allowEmpty is false', () => {
    const res = parseRefundAmount('', 1000, { allowEmpty: false });
    expect(res.valid).toBe(false);
    expect(res.error).toContain('Please enter a refund amount');
  });

  it('rejects zero or negative amounts', () => {
    const zeroRes = parseRefundAmount('0');
    expect(zeroRes.valid).toBe(false);
    expect(zeroRes.error).toContain('greater than zero');

    const zeroDecimal = parseRefundAmount('0.00');
    expect(zeroDecimal.valid).toBe(false);

    const neg = parseRefundAmount('-5.00');
    expect(neg.valid).toBe(false);
  });

  it('rejects inputs with more than two decimal places', () => {
    const res = parseRefundAmount('12.345');
    expect(res.valid).toBe(false);
    expect(res.error).toContain('cannot have more than two decimal places');
  });

  it('rejects non-numeric inputs', () => {
    expect(parseRefundAmount('abc').valid).toBe(false);
    expect(parseRefundAmount('12.34.56').valid).toBe(false);
    expect(parseRefundAmount('$').valid).toBe(false);
  });

  it('validates against remaining balance limit', () => {
    // Exactly at remaining balance
    expect(parseRefundAmount('15.00', 1500)).toEqual({ valid: true, cents: 1500 });

    // Exceeds remaining balance
    const over = parseRefundAmount('15.01', 1500);
    expect(over.valid).toBe(false);
    expect(over.error).toContain('cannot exceed the remaining balance');
    expect(over.error).toContain('$15.00');
  });
});

describe('createRefundRequestId', () => {
  it('generates an ID of 8 to 64 characters matching A-Za-z0-9-', () => {
    const id = createRefundRequestId();
    expect(id.length).toBeGreaterThanOrEqual(8);
    expect(id.length).toBeLessThanOrEqual(64);
    expect(/^[A-Za-z0-9-]+$/.test(id)).toBe(true);
  });

  it('generates distinct IDs on subsequent invocations', () => {
    const id1 = createRefundRequestId();
    const id2 = createRefundRequestId();
    expect(id1).not.toBe(id2);
  });
});

describe('remainingRefundableCents and canRefundPayment', () => {
  const basePayment: Payment = {
    id: 'pay_1',
    object: 'payment',
    amount: 2500,
    amountCaptured: 2500,
    amountRefunded: 500,
    currency: 'USD',
    status: 'captured',
    card: { brand: 'visa', last4: '4242' },
    reference: 'ref_123',
    createdAt: '2026-11-02T10:00:00Z',
  };

  it('computes remaining refundable cents correctly', () => {
    expect(remainingRefundableCents(basePayment)).toBe(2000);
    expect(canRefundPayment(basePayment)).toBe(true);
  });

  it('reports cannot refund when all money is refunded', () => {
    const fullyRefunded: Payment = {
      ...basePayment,
      amountRefunded: 2500,
    };
    expect(remainingRefundableCents(fullyRefunded)).toBe(0);
    expect(canRefundPayment(fullyRefunded)).toBe(false);
  });

  it('reports cannot refund for non-captured statuses', () => {
    const authorised: Payment = {
      ...basePayment,
      status: 'authorised',
    };
    expect(canRefundPayment(authorised)).toBe(false);

    const declined: Payment = {
      ...basePayment,
      status: 'declined',
    };
    expect(canRefundPayment(declined)).toBe(false);
  });
});
