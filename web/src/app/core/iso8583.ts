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
  '90': 'Original data',
  '100': 'Receiving institution',
  '102': 'Account id',
};

export interface FormattedIsoField {
  field: string;
  name: string;
  rawValue: string;
  formattedValue: string;
}

/** Formats an ISO 8583 field number and value according to specification. */
export function formatIsoField(fieldNum: string, rawValue: string): FormattedIsoField {
  const name = ISO_FIELD_NAMES[fieldNum] ?? `Field ${fieldNum}`;
  let formattedValue = rawValue;

  if (fieldNum === '4' || fieldNum === '6') {
    const dollars = formatIsoAmount(rawValue);
    formattedValue = `${rawValue} (${dollars})`;
  } else if (fieldNum === '49' || fieldNum === '51') {
    if (rawValue.trim() === '840') {
      formattedValue = '840 (US dollar)';
    }
  }

  return {
    field: fieldNum,
    name,
    rawValue,
    formattedValue,
  };
}
