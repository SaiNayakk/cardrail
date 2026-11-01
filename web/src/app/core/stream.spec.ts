import { describe, it, expect } from 'vitest';
import { mergeTraceEvents, shouldReconnect, STREAM_RECONNECT_MAX_AGE_MS } from './stream';
import { TraceEvent } from './types';

describe('SSE trace stream helpers', () => {
  const event1: TraceEvent = {
    id: 10,
    traceKey: 'trace_abc',
    reporter: 'railpay',
    from: 'merchant',
    to: 'railpay',
    kind: 'note',
    summary: 'Payment request received',
    at: '2026-11-02T10:00:00.000Z',
  };

  const event2: TraceEvent = {
    id: 11,
    traceKey: 'trace_abc',
    reporter: 'railnet',
    from: 'railnet',
    to: 'kestrel',
    kind: 'iso8583',
    mti: '0100',
    summary: 'Authorisation request',
    at: '2026-11-02T10:00:01.000Z',
  };

  const duplicateEvent1: TraceEvent = {
    ...event1,
    summary: 'Payment request received (replay)',
  };

  it('merges new events into existing list', () => {
    const initial = [event1];
    const result = mergeTraceEvents(initial, event2);
    expect(result).toHaveLength(2);
    expect(result[0].id).toBe(10);
    expect(result[1].id).toBe(11);
  });

  it('de-duplicates incoming events by id', () => {
    const initial = [event1];
    const result = mergeTraceEvents(initial, [duplicateEvent1, event2]);
    expect(result).toHaveLength(2);
    expect(result.map((e) => e.id)).toEqual([10, 11]);
  });

  it('sorts events in sequential order by id', () => {
    const result = mergeTraceEvents([], [event2, event1]);
    expect(result[0].id).toBe(10);
    expect(result[1].id).toBe(11);
  });

  it('orders events by when they were sent, even when a later one reached the trace first', () => {
    const sentFirst = { ...event1, id: 20, at: '2026-11-02T03:22:19.781Z' };
    const sentSecond = { ...event2, id: 19, at: '2026-11-02T03:22:19.834Z' };
    expect(mergeTraceEvents([], [sentSecond, sentFirst]).map((e) => e.id)).toEqual([20, 19]);
  });

  it('allows reconnection if last event was under two minutes ago', () => {
    const now = 1793500000000;
    const ninetySecondsAgo = new Date(now - 90 * 1000).toISOString();
    expect(shouldReconnect(ninetySecondsAgo, now)).toBe(true);
  });

  it('disallows reconnection if last event was older than two minutes', () => {
    const now = 1793500000000;
    const threeMinutesAgo = new Date(now - 180 * 1000).toISOString();
    expect(shouldReconnect(threeMinutesAgo, now)).toBe(false);
  });

  it('allows reconnection if no events have been received yet', () => {
    expect(shouldReconnect(null)).toBe(true);
    expect(shouldReconnect(undefined)).toBe(true);
  });
});
