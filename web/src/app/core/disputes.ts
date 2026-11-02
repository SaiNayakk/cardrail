import { codeOf, messageOf, problemOf } from './problem';
import { DisputeStatus } from './types';

export interface DisputeReasonOption {
  code: string;
  label: string;
}

/** The four standard cardholder dispute reasons in plain words. */
export const DISPUTE_REASONS: DisputeReasonOption[] = [
  { code: '1040', label: "I didn't make this payment" },
  { code: '1310', label: 'I never received it' },
  { code: '1330', label: "It wasn't as described" },
  { code: '1240', label: 'I was charged twice' },
];

export const DISPUTE_REASON_LABELS: Record<string, string> = {
  '1040': "I didn't make this payment",
  '1310': 'I never received it',
  '1330': "It wasn't as described",
  '1240': 'I was charged twice',
};

/** Formats a dispute reason code or description into clear plain words. */
export function disputeReasonLabel(codeOrReason: string | null | undefined): string {
  if (!codeOrReason) {
    return 'Disputed purchase';
  }
  const trimmed = codeOrReason.trim();
  const direct = DISPUTE_REASON_LABELS[trimmed];
  if (direct) {
    return direct;
  }

  const lower = trimmed.toLowerCase();
  if (lower.includes('not received') || lower.includes('1310')) {
    return 'I never received it';
  }
  if (lower.includes('fraud') || lower.includes("didn't make") || lower.includes('unauthorised') || lower.includes('1040')) {
    return "I didn't make this payment";
  }
  if (lower.includes('described') || lower.includes('defective') || lower.includes('1330')) {
    return "It wasn't as described";
  }
  if (lower.includes('twice') || lower.includes('duplicate') || lower.includes('1240')) {
    return 'I was charged twice';
  }

  return trimmed;
}

export const DISPUTE_STATUS_LABELS: Record<string, string> = {
  needs_response: 'Needs response',
  under_review: 'Under review',
  won: 'Won',
  lost: 'Lost',
};

/** Formats a dispute status into plain words. */
export function disputeStatusLabel(status: DisputeStatus | string | null | undefined): string {
  if (!status) {
    return 'Unknown';
  }
  return DISPUTE_STATUS_LABELS[status] ?? status;
}

/** Translates API problem responses for cardholder dispute attempts into friendly plain sentences. */
export function disputeErrorMessage(error: unknown): string {
  const code = codeOf(error);
  if (code === 'not_cleared') {
    return "This purchase hasn't reached the cardholder's bank yet. Try again in a minute.";
  }
  if (code === 'already_disputed') {
    return 'This payment has already been disputed.';
  }

  const problem = problemOf(error);
  if (problem) {
    if (problem.code === 'not_cleared' || (problem.detail && problem.detail.toLowerCase().includes('not cleared'))) {
      return "This purchase hasn't reached the cardholder's bank yet. Try again in a minute.";
    }
    if (problem.code === 'already_disputed' || (problem.detail && problem.detail.toLowerCase().includes('already disputed'))) {
      return 'This payment has already been disputed.';
    }
    if (problem.detail) {
      return problem.detail;
    }
  }

  return messageOf(error);
}
