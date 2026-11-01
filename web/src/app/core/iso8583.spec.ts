import { describe, it, expect } from 'vitest';
import { formatIsoField, ISO_FIELD_NAMES } from './iso8583';

describe('ISO 8583 field formatting', () => {
  it('maps known field numbers to standard descriptive names', () => {
    expect(ISO_FIELD_NAMES['2']).toBe('Card number (masked)');
    expect(ISO_FIELD_NAMES['11']).toBe('Trace number (STAN)');
    expect(ISO_FIELD_NAMES['39']).toBe('Response code');
    expect(ISO_FIELD_NAMES['70']).toBe('Network management code');
  });

  it('formats field 4 and 6 with dollar conversion', () => {
    const f4 = formatIsoField('4', '000000000450');
    expect(f4.name).toBe('Amount');
    expect(f4.rawValue).toBe('000000000450');
    expect(f4.formattedValue).toBe('000000000450 ($4.50)');

    const f6 = formatIsoField('6', '000000010000');
    expect(f6.name).toBe('Amount, cardholder billing');
    expect(f6.formattedValue).toBe('000000010000 ($100.00)');
  });

  it('formats field 49 and 51 as 840 (US dollar)', () => {
    const f49 = formatIsoField('49', '840');
    expect(f49.name).toBe('Currency');
    expect(f49.formattedValue).toBe('840 (US dollar)');

    const f51 = formatIsoField('51', '840');
    expect(f51.name).toBe('Billing currency');
    expect(f51.formattedValue).toBe('840 (US dollar)');
  });

  it('falls back gracefully for unknown field numbers', () => {
    const unknown = formatIsoField('999', 'test_val');
    expect(unknown.name).toBe('Field 999');
    expect(unknown.formattedValue).toBe('test_val');
  });
});
