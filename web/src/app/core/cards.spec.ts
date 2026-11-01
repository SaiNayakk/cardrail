import { describe, it, expect } from 'vitest';
import { groupCardNumber } from './cards';

describe('card number grouping', () => {
  it('groups a 16-digit card number in blocks of four', () => {
    expect(groupCardNumber('2860010000000012')).toBe('2860 0100 0000 0012');
  });

  it('groups masked card numbers in blocks of four', () => {
    expect(groupCardNumber('286001******0012')).toBe('2860 01** **** 0012');
  });

  it('removes existing spaces before regrouping', () => {
    expect(groupCardNumber('2860 01000000  0012')).toBe('2860 0100 0000 0012');
  });

  it('handles empty or null values', () => {
    expect(groupCardNumber('')).toBe('');
    expect(groupCardNumber(null)).toBe('');
    expect(groupCardNumber(undefined)).toBe('');
  });

  it('handles odd length numbers cleanly', () => {
    expect(groupCardNumber('371234567890123')).toBe('3712 3456 7890 123');
  });
});
