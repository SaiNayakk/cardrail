import { formatIsoAmount } from './money';

export const ISO_FIELD_NAMES: Record<string, string> = {
  '2': 'Card number (masked)',
  '3': 'Processing code',
  '4': 'Amount',
  '6': 'Amount, cardholder billing',
  '7': 'Sent at (MMDDhhmmss UTC)',
  '10': 'Conversion rate',
  '11': 'Trace number (STAN)',
  '12': 'Local time',
  '13': 'Local date',
  '14': 'Expiry (hidden)',
  '18': 'Merchant category code',
  '22': 'Entry mode',
  '25': 'Condition code',
  '32': 'Acquirer id',
  '33': 'Forwarding institution',
  '35': 'Track 2 (hidden)',
  '37': 'Retrieval reference',
  '38': 'Approval code',
  '39': 'Response code',
  '41': 'Terminal id',
  '42': 'Merchant id',
  '43': 'Merchant name and location',
  '48': 'Additional data',
  '49': 'Currency',
  '51': 'Billing currency',
  '52': 'PIN (hidden)',
  '55': 'Chip data (hidden)',
  '70': 'Network management code',
  '90': 'Original data elements',
  '95': 'Replacement amounts',
  '100': 'Receiving institution',
  '102': 'Account id',
};

export const MTI_NAMES: Record<string, string> = {
  '0100': 'Authorisation request',
  '0110': 'Authorisation response',
  '0120': 'Authorisation advice',
  '0130': 'Authorisation advice response',
  '0200': 'Financial transaction request',
  '0210': 'Financial transaction response',
  '0220': 'Financial advice',
  '0230': 'Financial advice response',
  '0400': 'Reversal request',
  '0410': 'Reversal response',
  '0420': 'Reversal advice',
  '0430': 'Reversal advice response',
  '0800': 'Network management request',
  '0810': 'Network management response',
};

/** Returns the plain name for an MTI code. */
export function getMtiName(mti: string | null | undefined): string {
  if (!mti) {
    return '';
  }
  return MTI_NAMES[mti] ?? `MTI ${mti}`;
}

export interface Field90Split {
  originalMti: string;
  traceNumber: string;
  sentAt: string;
  acquirer: string;
  forwardingInstitution: string;
}

/**
 * Splits an ISO 8583 Field 90 (Original data elements) string into its constituent parts:
 * original MTI (4), trace number (6), sent at (10), acquirer (11), forwarding institution (11).
 */
export function splitField90(raw: string | null | undefined): Field90Split | null {
  if (!raw) {
    return null;
  }
  const clean = raw.trim();
  if (clean.length < 4) {
    return null;
  }
  return {
    originalMti: clean.slice(0, 4),
    traceNumber: clean.slice(4, 10),
    sentAt: clean.slice(10, 20),
    acquirer: clean.slice(20, 31),
    forwardingInstitution: clean.slice(31, 42),
  };
}

export interface FormattedIsoField {
  field: string;
  name: string;
  rawValue: string;
  formattedValue: string;
  split90?: Field90Split | null;
}

/** Formats an ISO 8583 field number and value according to specification. */
export function formatIsoField(fieldNum: string, rawValue: string): FormattedIsoField {
  const name = ISO_FIELD_NAMES[fieldNum] ?? `Field ${fieldNum}`;
  let formattedValue = rawValue;
  let split90: Field90Split | null = null;

  if (fieldNum === '4' || fieldNum === '6') {
    const dollars = formatIsoAmount(rawValue);
    formattedValue = `${rawValue} (${dollars})`;
  } else if (fieldNum === '49' || fieldNum === '51') {
    if (rawValue.trim() === '840') {
      formattedValue = '840 (US dollar)';
    }
  } else if (fieldNum === '90') {
    split90 = splitField90(rawValue);
  }

  return {
    field: fieldNum,
    name,
    rawValue,
    formattedValue,
    ...(split90 ? { split90 } : {}),
  };
}
