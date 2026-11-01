import { WebhookEvent } from './types';

export const EVENT_TYPE_NAMES: Record<string, string> = {
  'payment.captured': 'Payment captured',
  'payment.authorised': 'Payment authorised',
  'payment.declined': 'Payment declined',
  'payment.reversed': 'Payment reversed',
  'payment.voided': 'Payment voided',
  'payment.failed': 'Payment failed',
  'payment.pending': 'Payment pending',
  'refund.succeeded': 'Refund succeeded',
  'refund.failed': 'Refund failed',
  'refund.pending': 'Refund pending',
};

/** Formats a webhook event type (e.g. "payment.captured") into plain words (e.g. "Payment captured"). */
export function formatEventType(type: string | null | undefined): string {
  if (!type) {
    return 'Event';
  }
  const direct = EVENT_TYPE_NAMES[type];
  if (direct) {
    return direct;
  }
  const words = type.replace(/[._-]+/g, ' ').trim();
  if (!words) {
    return type;
  }
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** Extracts the amount in cents from a webhook event data payload if present. */
export function getEventAmount(event: WebhookEvent | null | undefined): number | undefined {
  if (!event || !event.data || typeof event.data !== 'object') {
    return undefined;
  }
  const data = event.data as { amount?: number; amountCaptured?: number };
  if (typeof data.amount === 'number' && !Number.isNaN(data.amount)) {
    return data.amount;
  }
  if (typeof data.amountCaptured === 'number' && !Number.isNaN(data.amountCaptured)) {
    return data.amountCaptured;
  }
  return undefined;
}
