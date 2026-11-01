import { HttpErrorResponse } from '@angular/common/http';
import { Problem } from './types';

/** The RFC 9457 problem answered with by an API service, if available. */
export function problemOf(error: unknown): Problem | null {
  if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object') {
    const obj = error.error as Record<string, unknown>;
    if ('detail' in obj || 'title' in obj || 'code' in obj) {
      return obj as Problem;
    }
  }
  return null;
}

/** Plain sentence explaining the error to the user. */
export function messageOf(error: unknown): string {
  const problem = problemOf(error);
  if (problem) {
    return problem.detail ?? problem.title ?? 'Request failed.';
  }
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Cannot reach the server. Check your connection and try again.';
    }
    if (error.status === 404) {
      return 'Requested resource not found.';
    }
    if (error.status === 429) {
      return 'Too many requests in a short time. Please wait a moment.';
    }
    if (error.status >= 500) {
      return 'Something went wrong on our side. Try again: the same order is never charged twice.';
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'An unexpected error occurred. Please try again.';
}

export function codeOf(error: unknown): string {
  return problemOf(error)?.code ?? '';
}

/**
 * Whether the outcome is unknown and the same request should be sent again: the connection failed, or a server
 * failed after it may have acted. A 4xx is a definite answer.
 */
export function isRetryable(error: unknown): boolean {
  return error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 500);
}
