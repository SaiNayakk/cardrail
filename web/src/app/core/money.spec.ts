import { describe, it, expect } from 'vitest';
import { formatCents, formatIsoAmount } from './money';

describe('money formatting', () => {
  it('formats cents to US dollars', () => {
    expect(formatCents(450)).toBe('$4.50');
    expect(formatCents(0)).toBe('$0.00');
    expect(formatCents(123456)).toBe('$1,234.56');
    expect(formatCents(99)).toBe('$0.99');
  });

  it('handles null, undefined and NaN values safely', () => {
    expect(formatCents(null)).toBe('–');
    expect(formatCents(undefined)).toBe('–');
    expect(formatCents(Number.NaN)).toBe('–');
  });

  it('formats ISO 8583 12-digit minor unit strings to dollars', () => {
    expect(formatIsoAmount('000000000450')).toBe('$4.50');
    expect(formatIsoAmount('000000012500')).toBe('$125.00');
    expect(formatIsoAmount('000000000000')).toBe('$0.00');
  });

  it('returns original or dash for invalid ISO amounts', () => {
    expect(formatIsoAmount('')).toBe('–');
    expect(formatIsoAmount(undefined)).toBe('–');
    expect(formatIsoAmount('non-numeric')).toBe('non-numeric');
  });
});
