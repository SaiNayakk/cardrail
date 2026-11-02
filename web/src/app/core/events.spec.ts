import { describe, it, expect } from 'vitest';
import { formatEventType, getEventAmount, EVENT_TYPE_NAMES } from './events';
import { WebhookEvent } from './types';

describe('webhook event type plain words names', () => {
  it('formats standard payment event types into plain words', () => {
    expect(formatEventType('payment.captured')).toBe('Payment captured');
    expect(formatEventType('payment.authorised')).toBe('Payment authorised');
    expect(formatEventType('payment.declined')).toBe('Payment declined');
    expect(formatEventType('payment.reversed')).toBe('Payment reversed');
    expect(formatEventType('payment.voided')).toBe('Payment voided');
    expect(formatEventType('payment.failed')).toBe('Payment failed');
    expect(formatEventType('payment.pending')).toBe('Payment pending');
  });

  it('formats standard refund event types into plain words', () => {
    expect(formatEventType('refund.succeeded')).toBe('Refund succeeded');
    expect(formatEventType('refund.failed')).toBe('Refund failed');
    expect(formatEventType('refund.pending')).toBe('Refund pending');
  });

  it('formats dispute and payout event types into plain words', () => {
    expect(formatEventType('dispute.created')).toBe('Dispute created');
    expect(formatEventType('dispute.won')).toBe('Dispute won');
    expect(formatEventType('dispute.lost')).toBe('Dispute lost');
    expect(formatEventType('payout.paid')).toBe('Payout paid');
  });

  it('formats unknown event types gracefully using word boundary replacement', () => {
    expect(formatEventType('charge.dispute.created')).toBe('Charge dispute created');
    expect(formatEventType('merchant_account_updated')).toBe('Merchant account updated');
  });

  it('handles empty or missing event types safely', () => {
    expect(formatEventType('')).toBe('Event');
    expect(formatEventType(null)).toBe('Event');
    expect(formatEventType(undefined)).toBe('Event');
  });

  it('exports complete dictionary of recognized event names', () => {
    expect(EVENT_TYPE_NAMES['payment.captured']).toBe('Payment captured');
    expect(EVENT_TYPE_NAMES['refund.succeeded']).toBe('Refund succeeded');
  });
});

describe('getEventAmount', () => {
  it('extracts amount from payment event data', () => {
    const paymentEv: WebhookEvent = {
      id: 'evt_1',
      object: 'event',
      type: 'payment.captured',
      createdAt: '2026-11-02T10:00:00Z',
      data: {
        id: 'pay_1',
        object: 'payment',
        amount: 1999,
        amountCaptured: 1999,
        currency: 'USD',
        status: 'captured',
        card: { brand: 'visa', last4: '4242' },
        reference: 'ref_1',
        createdAt: '2026-11-02T10:00:00Z',
      },
    };
    expect(getEventAmount(paymentEv)).toBe(1999);
  });

  it('extracts amount from refund event data', () => {
    const refundEv: WebhookEvent = {
      id: 'evt_2',
      object: 'event',
      type: 'refund.succeeded',
      createdAt: '2026-11-02T10:05:00Z',
      data: {
        id: 're_1',
        object: 'refund',
        amount: 300,
        status: 'succeeded',
        reference: 'ref_2',
        payment: 'pay_1',
        createdAt: '2026-11-02T10:05:00Z',
      },
    };
    expect(getEventAmount(refundEv)).toBe(300);
  });

  it('extracts amount from dispute and payout event data', () => {
    const disputeEv: WebhookEvent = {
      id: 'evt_dsp',
      object: 'event',
      type: 'dispute.created',
      createdAt: '2026-11-02T10:10:00Z',
      data: {
        id: 'dsp_1',
        object: 'dispute',
        payment: 'pay_1',
        amount: 450,
        reason: 'not received',
        reasonCode: '1310',
        status: 'needs_response',
        createdAt: '2026-11-02T10:10:00Z',
      },
    };
    expect(getEventAmount(disputeEv)).toBe(450);

    const payoutEv: WebhookEvent = {
      id: 'evt_po',
      object: 'event',
      type: 'payout.paid',
      createdAt: '2026-11-02T10:15:00Z',
      data: {
        id: 'po_1',
        object: 'payout',
        amount: 9750,
        sales: 10000,
        refunds: 0,
        fees: 250,
        disputes: 0,
        createdAt: '2026-11-02T10:15:00Z',
      },
    };
    expect(getEventAmount(payoutEv)).toBe(9750);
  });

  it('returns undefined when no amount is present', () => {
    const emptyEv: WebhookEvent = {
      id: 'evt_3',
      object: 'event',
      type: 'payment.failed',
      createdAt: '2026-11-02T10:00:00Z',
      data: {},
    };
    expect(getEventAmount(emptyEv)).toBeUndefined();
    expect(getEventAmount(null)).toBeUndefined();
  });
});
