import { describe, it, expect } from 'vitest';
import {
  formatTransactionStatus,
  formatPaymentStatus,
  parseDollarLimit,
  BLOCKED_MCC_CATEGORIES,
  getMccLabel,
  isStepUpAuthenticationMessage,
  isSharedCardProblem,
  isNotConfirmedYet,
  confirmPaymentWithRetry,
} from './cardholder';
import { HttpErrorResponse } from '@angular/common/http';

describe('status words for transactions', () => {
  it('formats APPROVED as "Held"', () => {
    expect(formatTransactionStatus('APPROVED')).toBe('Held');
    expect(formatTransactionStatus('approved')).toBe('Held');
  });

  it('formats CLEARED as "Paid"', () => {
    expect(formatTransactionStatus('CLEARED')).toBe('Paid');
    expect(formatTransactionStatus('cleared')).toBe('Paid');
  });

  it('formats DECLINED with responseText as "Declined: <responseText>"', () => {
    expect(formatTransactionStatus('DECLINED', 'Insufficient funds')).toBe(
      'Declined: Insufficient funds',
    );
    expect(formatTransactionStatus('declined', 'Card frozen')).toBe(
      'Declined: Card frozen',
    );
  });

  it('formats DECLINED without responseText as "Declined"', () => {
    expect(formatTransactionStatus('DECLINED')).toBe('Declined');
    expect(formatTransactionStatus('DECLINED', '')).toBe('Declined');
    expect(formatTransactionStatus('DECLINED', null)).toBe('Declined');
  });

  it('formats REVERSED as "Cancelled"', () => {
    expect(formatTransactionStatus('REVERSED')).toBe('Cancelled');
  });

  it('formats EXPIRED as "Released"', () => {
    expect(formatTransactionStatus('EXPIRED')).toBe('Released');
  });

  it('handles null, undefined, or unknown status cleanly', () => {
    expect(formatTransactionStatus(null)).toBe('Unknown');
    expect(formatTransactionStatus(undefined)).toBe('Unknown');
    expect(formatTransactionStatus('CUSTOM_STATUS')).toBe('CUSTOM_STATUS');
  });
});

describe('status words for payments', () => {
  it('formats requires_action as "Waiting for the cardholder"', () => {
    expect(formatPaymentStatus('requires_action')).toBe('Waiting for the cardholder');
    expect(formatPaymentStatus('REQUIRES_ACTION')).toBe('Waiting for the cardholder');
  });

  it('formats standard payment outcomes in plain words', () => {
    expect(formatPaymentStatus('captured')).toBe('Captured');
    expect(formatPaymentStatus('authorised')).toBe('Authorised');
    expect(formatPaymentStatus('declined')).toBe('Declined');
    expect(formatPaymentStatus('reversed')).toBe('Reversed');
    expect(formatPaymentStatus('voided')).toBe('Voided');
    expect(formatPaymentStatus('failed')).toBe('Failed');
    expect(formatPaymentStatus('pending')).toBe('Pending');
    expect(formatPaymentStatus('unknown')).toBe('Unknown');
  });

  it('handles null or undefined cleanly', () => {
    expect(formatPaymentStatus(null)).toBe('Unknown');
    expect(formatPaymentStatus(undefined)).toBe('Unknown');
  });
});

describe('dollars-to-cents parsing for limits', () => {
  it('parses whole dollar amounts into cents', () => {
    expect(parseDollarLimit('50')).toEqual({ valid: true, cents: 5000 });
    expect(parseDollarLimit('100')).toEqual({ valid: true, cents: 10000 });
  });

  it('parses dollars and cents strings', () => {
    expect(parseDollarLimit('50.00')).toEqual({ valid: true, cents: 5000 });
    expect(parseDollarLimit('19.99')).toEqual({ valid: true, cents: 1999 });
    expect(parseDollarLimit('0.50')).toEqual({ valid: true, cents: 50 });
  });

  it('handles leading dollar sign, spaces, and commas', () => {
    expect(parseDollarLimit('$50')).toEqual({ valid: true, cents: 5000 });
    expect(parseDollarLimit('  $ 150.75  ')).toEqual({ valid: true, cents: 15075 });
    expect(parseDollarLimit('$1,000.00')).toEqual({ valid: true, cents: 100000 });
  });

  it('treats empty or whitespace inputs as omitted (null cents)', () => {
    expect(parseDollarLimit('')).toEqual({ valid: true, cents: null });
    expect(parseDollarLimit('   ')).toEqual({ valid: true, cents: null });
    expect(parseDollarLimit(null)).toEqual({ valid: true, cents: null });
    expect(parseDollarLimit(undefined)).toEqual({ valid: true, cents: null });
    expect(parseDollarLimit('$')).toEqual({ valid: true, cents: null });
  });

  it('rejects negative numbers', () => {
    const res = parseDollarLimit('-25');
    expect(res.valid).toBe(false);
    expect(res.error).toContain('greater than zero');
  });

  it('rejects amounts with more than two decimal places', () => {
    const res = parseDollarLimit('50.123');
    expect(res.valid).toBe(false);
    expect(res.error).toContain('more than two decimal places');
  });

  it('rejects non-numeric inputs', () => {
    const res = parseDollarLimit('abc');
    expect(res.valid).toBe(false);
    expect(res.error).toContain('valid dollar amount');
  });
});

describe('MCC category labels', () => {
  it('contains the four standard blockable categories', () => {
    const codes = BLOCKED_MCC_CATEGORIES.map((c) => c.code);
    expect(codes).toContain('7995');
    expect(codes).toContain('4829');
    expect(codes).toContain('6051');
    expect(codes).toContain('5967');
  });

  it('maps category codes to plain words labels', () => {
    expect(getMccLabel('7995')).toBe('Gambling');
    expect(getMccLabel('4829')).toBe('Money transfers');
    expect(getMccLabel('6051')).toBe('Cash-like');
    expect(getMccLabel('5967')).toBe('Direct marketing');
  });

  it('returns code itself or unknown for unrecognised codes', () => {
    expect(getMccLabel('9999')).toBe('9999');
    expect(getMccLabel(null)).toBe('Unknown category');
  });
});

describe('step-up iframe message filter', () => {
  const origin = 'https://shop.example.com';

  it('accepts valid authentication message with status "Y"', () => {
    const msg = {
      type: 'cardrail:authentication',
      id: 'acs_12345',
      status: 'Y',
    };
    expect(isStepUpAuthenticationMessage(msg, origin, origin)).toBe(true);
  });

  it('accepts valid authentication message with status "N"', () => {
    const msg = {
      type: 'cardrail:authentication',
      id: 'acs_67890',
      status: 'N',
    };
    expect(isStepUpAuthenticationMessage(msg, origin, origin)).toBe(true);
  });

  it('rejects messages from a different origin', () => {
    const msg = {
      type: 'cardrail:authentication',
      id: 'acs_12345',
      status: 'Y',
    };
    expect(isStepUpAuthenticationMessage(msg, 'https://attacker.com', origin)).toBe(false);
  });

  it('rejects status values other than "Y" and "N"', () => {
    const invalidStatuses = ['PENDING', 'U', 'A', 'y', 'n', 'true', true, 1, null, undefined];
    for (const st of invalidStatuses) {
      const msg = {
        type: 'cardrail:authentication',
        id: 'acs_123',
        status: st,
      };
      expect(isStepUpAuthenticationMessage(msg, origin, origin)).toBe(false);
    }
  });

  it('rejects message with wrong type', () => {
    const msg = {
      type: 'cardrail:token',
      id: 'acs_123',
      status: 'Y',
    };
    expect(isStepUpAuthenticationMessage(msg, origin, origin)).toBe(false);
  });

  it('rejects message with missing or empty id', () => {
    expect(
      isStepUpAuthenticationMessage(
        { type: 'cardrail:authentication', id: '', status: 'Y' },
        origin,
        origin,
      ),
    ).toBe(false);
    expect(
      isStepUpAuthenticationMessage(
        { type: 'cardrail:authentication', status: 'Y' },
        origin,
        origin,
      ),
    ).toBe(false);
  });

  it('rejects non-object or null data', () => {
    expect(isStepUpAuthenticationMessage(null, origin, origin)).toBe(false);
    expect(isStepUpAuthenticationMessage(undefined, origin, origin)).toBe(false);
    expect(isStepUpAuthenticationMessage('string', origin, origin)).toBe(false);
    expect(isStepUpAuthenticationMessage(123, origin, origin)).toBe(false);
  });
});

describe('problem checkers', () => {
  it('detects 403 shared_card problem', () => {
    const httpError = new HttpErrorResponse({
      status: 403,
      error: { code: 'shared_card', detail: 'This is a shared test card.' },
    });
    expect(isSharedCardProblem(httpError)).toBe(true);
  });

  it('detects 409 not_confirmed_yet problem', () => {
    const httpError = new HttpErrorResponse({
      status: 409,
      error: { code: 'not_confirmed_yet', detail: 'Challenge in progress.' },
    });
    expect(isNotConfirmedYet(httpError)).toBe(true);
  });

  it('retries up to 3 times on 409 not_confirmed_yet', async () => {
    let callCount = 0;
    const mockPayment = {
      id: 'pay_123',
      object: 'payment' as const,
      amount: 1000,
      currency: 'USD',
      status: 'captured' as const,
      card: { brand: 'visa', last4: '4242' },
      reference: 'ref_1',
      createdAt: '2026-11-02T10:00:00Z',
    };
    const mockApi = {
      confirmPayment: async () => {
        callCount++;
        if (callCount < 3) {
          throw new HttpErrorResponse({
            status: 409,
            error: { code: 'not_confirmed_yet' },
          });
        }
        return mockPayment;
      },
    };

    const res = await confirmPaymentWithRetry(mockApi, 'pay_123', 3, 10);
    expect(res).toEqual(mockPayment);
    expect(callCount).toBe(3);
  });

  it('throws when max retries are exceeded', async () => {
    let callCount = 0;
    const mockApi = {
      confirmPayment: async () => {
        callCount++;
        throw new HttpErrorResponse({
          status: 409,
          error: { code: 'not_confirmed_yet' },
        });
      },
    };

    await expect(confirmPaymentWithRetry(mockApi, 'pay_123', 2, 10)).rejects.toThrow();
    expect(callCount).toBe(3); // 1 initial + 2 retries
  });
});
