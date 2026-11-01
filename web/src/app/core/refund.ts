import { formatCents } from './money';
import { Payment } from './types';

export interface RefundAmountResult {
  valid: boolean;
  cents?: number;
  error?: string;
}

export interface ParseRefundAmountOptions {
  allowEmpty?: boolean;
}

/** Generates a new random request id between 8 and 64 characters (A-Za-z0-9-). */
export function createRefundRequestId(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const array = new Uint8Array(24);
  crypto.getRandomValues(array);
  let id = 'req-';
  for (const byte of array) {
    id += chars[byte % chars.length];
  }
  return id;
}

/**
 * Parses and validates an input string representing a refund amount in dollars and cents.
 *
 * If the input is empty or whitespace and allowEmpty is true (the default), returns
 * `{ valid: true, cents: undefined }` so the amount can be omitted to refund all remaining funds.
 */
export function parseRefundAmount(
  raw: string | null | undefined,
  maxCents?: number,
  options: ParseRefundAmountOptions = {},
): RefundAmountResult {
  const allowEmpty = options.allowEmpty ?? true;

  if (raw === null || raw === undefined || raw.trim() === '') {
    if (allowEmpty) {
      return { valid: true, cents: undefined };
    }
    return { valid: false, error: 'Please enter a refund amount.' };
  }

  // Strip dollar sign and whitespace
  let clean = raw.trim();
  if (clean.startsWith('$')) {
    clean = clean.slice(1).trim();
  }
  // Strip thousands separators
  clean = clean.replace(/,/g, '');

  if (!clean) {
    // something was typed ("$"), just not an amount: that isn't "refund all of it"
    return { valid: false, error: 'Enter an amount in dollars and cents, like 4.50.' };
  }

  // Strict regex for decimal dollars and cents
  const match = /^(\d+)?(?:\.(\d+))?$/.exec(clean);
  if (!match) {
    return { valid: false, error: 'Please enter a valid amount in dollars and cents.' };
  }

  const [, intPart, fracPart] = match;
  if (!intPart && !fracPart) {
    return { valid: false, error: 'Please enter a valid amount in dollars and cents.' };
  }

  if (fracPart && fracPart.length > 2) {
    return { valid: false, error: 'Amount cannot have more than two decimal places.' };
  }

  const intVal = intPart ? Number.parseInt(intPart, 10) : 0;
  if (Number.isNaN(intVal)) {
    return { valid: false, error: 'Please enter a valid amount in dollars and cents.' };
  }

  const fracVal = fracPart ? Number.parseInt(fracPart.padEnd(2, '0').slice(0, 2), 10) : 0;
  const totalCents = intVal * 100 + fracVal;

  if (totalCents <= 0) {
    return { valid: false, error: 'Refund amount must be greater than zero.' };
  }

  if (maxCents !== undefined && totalCents > maxCents) {
    return {
      valid: false,
      error: `Amount cannot exceed the remaining balance of ${formatCents(maxCents)}.`,
    };
  }

  return { valid: true, cents: totalCents };
}

/** Computes the amount in cents still available to refund for a payment. */
export function remainingRefundableCents(payment: Payment): number {
  const captured = payment.amountCaptured ?? (payment.status === 'captured' ? payment.amount : 0);
  const refunded = payment.amountRefunded ?? 0;
  return Math.max(0, captured - refunded);
}

/** Determines whether a payment has remaining captured funds that can be refunded. */
export function canRefundPayment(payment: Payment): boolean {
  return payment.status === 'captured' && remainingRefundableCents(payment) > 0;
}
