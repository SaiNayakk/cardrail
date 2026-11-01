import { describe, it, expect } from 'vitest';
import {
  formatIsoField,
  splitField90,
  getMtiName,
  ISO_FIELD_NAMES,
  MTI_NAMES,
} from './iso8583';

describe('ISO 8583 field formatting', () => {
  it('maps known field numbers to standard descriptive names including fields 90 and 95', () => {
    expect(ISO_FIELD_NAMES['2']).toBe('Card number (masked)');
    expect(ISO_FIELD_NAMES['11']).toBe('Trace number (STAN)');
    expect(ISO_FIELD_NAMES['39']).toBe('Response code');
    expect(ISO_FIELD_NAMES['70']).toBe('Network management code');
    expect(ISO_FIELD_NAMES['90']).toBe('Original data elements');
    expect(ISO_FIELD_NAMES['95']).toBe('Replacement amounts');
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

describe('field 90 splitter', () => {
  it('splits field 90 into original MTI, trace number, sent at, acquirer, and forwarding institution', () => {
    // 4 + 6 + 10 + 11 + 11 = 42 chars
    const raw = '010000012311021200000000000012300000000456';
    const split = splitField90(raw);
    expect(split).not.toBeNull();
    expect(split?.originalMti).toBe('0100');
    expect(split?.traceNumber).toBe('000123');
    expect(split?.sentAt).toBe('1102120000');
    expect(split?.acquirer).toBe('00000000123');
    expect(split?.forwardingInstitution).toBe('00000000456');
  });

  it('includes split90 object in formatIsoField result for field 90', () => {
    const raw = '010000004211020345000000000000100000000001';
    const formatted = formatIsoField('90', raw);
    expect(formatted.name).toBe('Original data elements');
    expect(formatted.split90).toBeDefined();
    expect(formatted.split90?.originalMti).toBe('0100');
    expect(formatted.split90?.traceNumber).toBe('000042');
    expect(formatted.split90?.sentAt).toBe('1102034500');
    expect(formatted.split90?.acquirer).toBe('00000000001');
    expect(formatted.split90?.forwardingInstitution).toBe('00000000001');
  });

  it('handles null, empty, or short strings safely', () => {
    expect(splitField90(null)).toBeNull();
    expect(splitField90('')).toBeNull();
    expect(splitField90('01')).toBeNull();
  });
});

describe('MTI plain names', () => {
  it('maps authorisation and reversal advice MTIs to plain names', () => {
    expect(getMtiName('0120')).toBe('Authorisation advice');
    expect(getMtiName('0130')).toBe('Authorisation advice response');
    expect(getMtiName('0400')).toBe('Reversal request');
    expect(getMtiName('0410')).toBe('Reversal response');
    expect(getMtiName('0420')).toBe('Reversal advice');
    expect(getMtiName('0430')).toBe('Reversal advice response');
  });

  it('maps standard 0100 and 0110 MTIs', () => {
    expect(getMtiName('0100')).toBe('Authorisation request');
    expect(getMtiName('0110')).toBe('Authorisation response');
  });

  it('falls back gracefully for unknown MTIs', () => {
    expect(getMtiName('0999')).toBe('MTI 0999');
    expect(getMtiName('')).toBe('');
    expect(getMtiName(null)).toBe('');
  });

  it('exports MTI_NAMES dictionary', () => {
    expect(MTI_NAMES['0120']).toBe('Authorisation advice');
    expect(MTI_NAMES['0400']).toBe('Reversal request');
  });
});
