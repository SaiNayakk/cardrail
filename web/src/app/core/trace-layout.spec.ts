import { describe, it, expect } from 'vitest';
import { DEFAULT_LANES, getLaneIndex, layoutEventArrow, layoutTraceArrows } from './trace-layout';
import { TraceEvent } from './types';

describe('trace layout', () => {
  const sampleEvent1: TraceEvent = {
    id: 1,
    traceKey: 'trace_123',
    reporter: 'railpay',
    from: 'merchant',
    to: 'railpay',
    kind: 'note',
    summary: 'Payment initiation',
    at: '2026-11-02T10:00:00.123Z',
  };

  const sampleEvent2: TraceEvent = {
    id: 2,
    traceKey: 'trace_123',
    reporter: 'railnet',
    from: 'railnet',
    to: 'kestrel',
    kind: 'iso8583',
    mti: '0100',
    summary: 'Authorisation request',
    at: '2026-11-02T10:00:00.456Z',
  };

  const sampleEvent3: TraceEvent = {
    id: 3,
    traceKey: 'trace_123',
    reporter: 'kestrel',
    from: 'kestrel',
    to: 'railnet',
    kind: 'iso8583',
    mti: '0110',
    summary: 'Authorisation response',
    at: '2026-11-02T10:00:00.789Z',
  };

  it('correctly maps lane indices for standard ecosystem parties', () => {
    expect(getLaneIndex('merchant')).toBe(0);
    expect(getLaneIndex('railpay')).toBe(1);
    expect(getLaneIndex('railpay-acquiring')).toBe(2);
    expect(getLaneIndex('railnet')).toBe(3);
    expect(getLaneIndex('kestrel')).toBe(4);
  });

  it('computes forward arrow direction and geometry', () => {
    const layout = layoutEventArrow(sampleEvent1, DEFAULT_LANES);
    expect(layout.direction).toBe('forward');
    expect(layout.fromIndex).toBe(0);
    expect(layout.toIndex).toBe(1);
    expect(layout.startPercent).toBe(10);
    expect(layout.endPercent).toBe(30);
    expect(layout.leftPercent).toBe(10);
    expect(layout.widthPercent).toBe(20);
    expect(layout.label).toBe('Payment initiation');
  });

  it('formats MTI with summary for ISO 8583 events', () => {
    const layout = layoutEventArrow(sampleEvent2, DEFAULT_LANES);
    expect(layout.label).toBe('0100 Authorisation request');
    expect(layout.direction).toBe('forward');
    expect(layout.fromIndex).toBe(3);
    expect(layout.toIndex).toBe(4);
  });

  it('computes backward arrow direction when response travels right to left', () => {
    const layout = layoutEventArrow(sampleEvent3, DEFAULT_LANES);
    expect(layout.direction).toBe('backward');
    expect(layout.fromIndex).toBe(4);
    expect(layout.toIndex).toBe(3);
    expect(layout.startPercent).toBe(90);
    expect(layout.endPercent).toBe(70);
    expect(layout.leftPercent).toBe(70);
    expect(layout.widthPercent).toBe(20);
  });

  it('detects stand-in events when summary contains stands in', () => {
    const standInEvent: TraceEvent = {
      id: 4,
      traceKey: 'trace_123',
      reporter: 'railnet',
      from: 'railnet',
      to: 'railpay-acquiring',
      kind: 'note',
      summary: 'No answer within 3000 ms: Railnet stands in and approves, telling the issuer later (0120)',
      at: '2026-11-02T10:00:03.000Z',
    };
    const layout = layoutEventArrow(standInEvent, DEFAULT_LANES);
    expect(layout.isStandIn).toBe(true);

    const normalLayout = layoutEventArrow(sampleEvent1, DEFAULT_LANES);
    expect(normalLayout.isStandIn).toBe(false);
  });

  it('populates mtiName for ISO 8583 events', () => {
    const layout = layoutEventArrow(sampleEvent2, DEFAULT_LANES);
    expect(layout.mtiName).toBe('Authorisation request');
  });

  it('maps an entire list of events in chronological order', () => {
    const arrows = layoutTraceArrows([sampleEvent1, sampleEvent2, sampleEvent3]);
    expect(arrows).toHaveLength(3);
    expect(arrows[0].event.id).toBe(1);
    expect(arrows[1].event.id).toBe(2);
    expect(arrows[2].event.id).toBe(3);
  });
});
