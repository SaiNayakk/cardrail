import { describe, it, expect } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import { messageOf, codeOf, problemOf, isRetryable } from './problem';

describe('problem', () => {
  it('extracts RFC 9457 problem detail and code', () => {
    const error = new HttpErrorResponse({
      status: 422,
      error: {
        status: 422,
        code: 'token_used',
        detail: 'The token has already been consumed.',
      },
    });

    expect(messageOf(error)).toBe('The token has already been consumed.');
    expect(codeOf(error)).toBe('token_used');
    expect(problemOf(error)?.status).toBe(422);
  });

  it('falls back to title when detail is absent', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: {
        title: 'Invalid checkout request',
        code: 'bad_request',
      },
    });

    expect(messageOf(error)).toBe('Invalid checkout request');
  });

  it('provides honest messages for network, 404 and 500 responses', () => {
    expect(messageOf(new HttpErrorResponse({ status: 0 }))).toContain('Cannot reach the server');
    expect(messageOf(new HttpErrorResponse({ status: 404 }))).toBe('Requested resource not found.');
    expect(messageOf(new HttpErrorResponse({ status: 500 }))).toContain('never charged twice');
  });

  it('handles standard Error instances and unknown objects', () => {
    expect(messageOf(new Error('Connection timed out'))).toBe('Connection timed out');
    expect(messageOf('unexpected string')).toBe('An unexpected error occurred. Please try again.');
  });
});

describe('isRetryable', () => {
  it('retries when the outcome is unknown, never after a definite answer', () => {
    expect(isRetryable(new HttpErrorResponse({ status: 0 }))).toBe(true);
    expect(isRetryable(new HttpErrorResponse({ status: 502 }))).toBe(true);
    expect(isRetryable(new HttpErrorResponse({ status: 422 }))).toBe(false);
    expect(isRetryable(new Error('typo'))).toBe(false);
  });
});
