import { HttpErrorResponse } from '@angular/common/http';
import { describe, it, expect } from 'vitest';
import {
  DISPUTE_REASONS,
  disputeErrorMessage,
  disputeReasonLabel,
  disputeStatusLabel,
} from './disputes';

describe('dispute reason labels', () => {
  it('maps known 4-digit dispute reason codes to plain words', () => {
    expect(disputeReasonLabel('1040')).toBe("I didn't make this payment");
    expect(disputeReasonLabel('1310')).toBe('I never received it');
    expect(disputeReasonLabel('1330')).toBe("It wasn't as described");
    expect(disputeReasonLabel('1240')).toBe('I was charged twice');
  });

  it('handles textual reason descriptions from backend gracefully', () => {
    expect(disputeReasonLabel('not received')).toBe('I never received it');
    expect(disputeReasonLabel('unauthorised fraud')).toBe("I didn't make this payment");
    expect(disputeReasonLabel('defective product')).toBe("It wasn't as described");
    expect(disputeReasonLabel('duplicate charge')).toBe('I was charged twice');
  });

  it('handles unknown or empty reason codes safely', () => {
    expect(disputeReasonLabel(null)).toBe('Disputed purchase');
    expect(disputeReasonLabel('')).toBe('Disputed purchase');
    expect(disputeReasonLabel('custom_reason')).toBe('custom_reason');
  });

  it('exports standard dispute reason choices for the user form', () => {
    expect(DISPUTE_REASONS).toHaveLength(4);
    expect(DISPUTE_REASONS.map((r) => r.code)).toEqual(['1040', '1310', '1330', '1240']);
  });

  it('maps dispute statuses to plain words', () => {
    expect(disputeStatusLabel('needs_response')).toBe('Needs response');
    expect(disputeStatusLabel('under_review')).toBe('Under review');
    expect(disputeStatusLabel('won')).toBe('Won');
    expect(disputeStatusLabel('lost')).toBe('Lost');
    expect(disputeStatusLabel(null)).toBe('Unknown');
  });

  it('translates dispute errors into plain words sentences', () => {
    const notClearedError = new HttpErrorResponse({
      status: 409,
      error: { code: 'not_cleared', detail: 'Purchase not yet cleared in settlement cycle' },
    });
    expect(disputeErrorMessage(notClearedError)).toBe(
      "This purchase hasn't reached the cardholder's bank yet. Try again in a minute.",
    );

    const alreadyDisputedError = new HttpErrorResponse({
      status: 409,
      error: { code: 'already_disputed' },
    });
    expect(disputeErrorMessage(alreadyDisputedError)).toBe(
      'This payment has already been disputed.',
    );
  });
});
