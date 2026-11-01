const USD_FORMAT = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Formats an integer amount of cents into US dollars (e.g. 450 -> "$4.50"). */
export function formatCents(cents: number | null | undefined): string {
  if (cents === null || cents === undefined || Number.isNaN(cents)) {
    return '–';
  }
  return USD_FORMAT.format(cents / 100);
}

/** Formats an ISO 8583 fixed 12-digit amount string (minor units) to dollars (e.g. "000000000450" -> "$4.50"). */
export function formatIsoAmount(raw: string | null | undefined): string {
  if (!raw) {
    return '–';
  }
  const trimmed = raw.trim();
  const parsed = Number.parseInt(trimmed, 10);
  if (Number.isNaN(parsed)) {
    return raw;
  }
  return formatCents(parsed);
}
