import { HttpErrorResponse } from '@angular/common/http';
import { codeOf, problemOf } from './problem';
import { CardholderTransactionStatus, Payment } from './types';

export interface StepUpAuthenticationMessage {
  type: 'cardrail:authentication';
  id: string;
  status: 'Y' | 'N';
}

/** Formats a cardholder transaction status into plain words. */
export function formatTransactionStatus(
  status: CardholderTransactionStatus | string | null | undefined,
  responseText?: string | null,
): string {
  if (!status) {
    return 'Unknown';
  }
  const upper = status.toUpperCase();
  if (upper === 'APPROVED') {
    return 'Held';
  }
  if (upper === 'CLEARED') {
    return 'Paid';
  }
  if (upper === 'DECLINED') {
    const text = responseText?.trim();
    return text ? `Declined: ${text}` : 'Declined';
  }
  if (upper === 'REVERSED') {
    return 'Cancelled';
  }
  if (upper === 'EXPIRED') {
    return 'Released';
  }
  return status;
}

/** Formats a payment status into plain words for display. */
export function formatPaymentStatus(status: string | null | undefined): string {
  if (!status) {
    return 'Unknown';
  }
  const s = status.toLowerCase();
  switch (s) {
    case 'requires_action':
      return 'Waiting for the cardholder';
    case 'captured':
      return 'Captured';
    case 'authorised':
      return 'Authorised';
    case 'declined':
      return 'Declined';
    case 'reversed':
      return 'Reversed';
    case 'voided':
      return 'Voided';
    case 'failed':
      return 'Failed';
    case 'pending':
      return 'Pending';
    case 'unknown':
      return 'Unknown';
    default:
      return status;
  }
}

export interface MccCategory {
  code: string;
  label: string;
}

/** Known blocked merchant categories with plain words labels. */
export const BLOCKED_MCC_CATEGORIES: MccCategory[] = [
  { code: '7995', label: 'Gambling' },
  { code: '4829', label: 'Money transfers' },
  { code: '6051', label: 'Cash-like' },
  { code: '5967', label: 'Direct marketing' },
];

export const MCC_LABELS: Record<string, string> = {
  '7995': 'Gambling',
  '4829': 'Money transfers',
  '6051': 'Cash-like',
  '5967': 'Direct marketing',
};

/** Returns the plain word label for an MCC code. */
export function getMccLabel(code: string | null | undefined): string {
  if (!code) {
    return 'Unknown category';
  }
  return MCC_LABELS[code] ?? code;
}

export interface ParseLimitResult {
  valid: boolean;
  cents?: number | null;
  error?: string;
}

/**
 * Parses an optional dollar limit string into integer cents.
 * Blank or omitted input returns { valid: true, cents: null }.
 * Strips optional leading '$' and commas.
 * Rejects negative values, more than two decimal places, or non-numeric input.
 */
export function parseDollarLimit(raw: string | null | undefined): ParseLimitResult {
  if (raw === null || raw === undefined) {
    return { valid: true, cents: null };
  }
  const trimmed = raw.trim();
  if (trimmed === '') {
    return { valid: true, cents: null };
  }

  const clean = trimmed.replace(/^\$\s*/, '').replace(/,/g, '').trim();
  if (clean === '') {
    return { valid: true, cents: null };
  }

  if (clean.startsWith('-')) {
    return { valid: false, error: 'Limit must be greater than zero.' };
  }

  if (!/^\d+(\.\d+)?$/.test(clean)) {
    return { valid: false, error: 'Please enter a valid dollar amount.' };
  }

  if (/^\d+\.\d{3,}$/.test(clean)) {
    return { valid: false, error: 'Limit cannot have more than two decimal places.' };
  }

  const num = Number.parseFloat(clean);
  if (Number.isNaN(num) || num < 0) {
    return { valid: false, error: 'Limit must be greater than zero.' };
  }

  return { valid: true, cents: Math.round(num * 100) };
}

/**
 * Filters and validates postMessage payloads from the step-up challenge iframe.
 * Only accepts messages from expectedOrigin where type is "cardrail:authentication"
 * and status is strictly "Y" or "N".
 */
export function isStepUpAuthenticationMessage(
  data: unknown,
  messageOrigin: string,
  expectedOrigin: string,
): data is StepUpAuthenticationMessage {
  if (!messageOrigin || !expectedOrigin || messageOrigin !== expectedOrigin) {
    return false;
  }
  if (!data || typeof data !== 'object') {
    return false;
  }
  const obj = data as Record<string, unknown>;
  if (obj['type'] !== 'cardrail:authentication') {
    return false;
  }
  if (typeof obj['id'] !== 'string' || obj['id'].trim() === '') {
    return false;
  }
  const status = obj['status'];
  if (status !== 'Y' && status !== 'N') {
    return false;
  }
  return true;
}

/** Checks whether an API error corresponds to a 403 shared_card problem. */
export function isSharedCardProblem(error: unknown): boolean {
  if (codeOf(error) === 'shared_card') {
    return true;
  }
  const problem = problemOf(error);
  if (problem?.code === 'shared_card') {
    return true;
  }
  if (error instanceof HttpErrorResponse && error.status === 403) {
    if (typeof error.error === 'object' && error.error && 'code' in error.error) {
      return (error.error as { code: string }).code === 'shared_card';
    }
  }
  return false;
}

/** Checks whether a confirmation error is a 409 not_confirmed_yet problem. */
export function isNotConfirmedYet(error: unknown): boolean {
  if (codeOf(error) === 'not_confirmed_yet') {
    return true;
  }
  const problem = problemOf(error);
  if (problem?.code === 'not_confirmed_yet') {
    return true;
  }
  if (error instanceof HttpErrorResponse && error.status === 409) {
    if (typeof error.error === 'object' && error.error && 'code' in error.error) {
      return (error.error as { code: string }).code === 'not_confirmed_yet';
    }
    return true;
  }
  return false;
}

/**
 * Calls confirmPayment on the given payment ID.
 * If a 409 not_confirmed_yet problem is encountered, retries up to maxRetries times,
 * waiting delayMs between retries (defaults to 3 retries, 1000 ms apart).
 */
export async function confirmPaymentWithRetry(
  api: { confirmPayment: (id: string) => Promise<Payment> },
  paymentId: string,
  maxRetries = 3,
  delayMs = 1000,
): Promise<Payment> {
  let attempt = 0;
  while (true) {
    try {
      return await api.confirmPayment(paymentId);
    } catch (err: unknown) {
      if (attempt < maxRetries && isNotConfirmedYet(err)) {
        attempt++;
        await new Promise((resolve) => setTimeout(resolve, delayMs));
        continue;
      }
      throw err;
    }
  }
}

