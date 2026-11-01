import { getMtiName } from './iso8583';
import { PARTY_NAMES, TraceEvent } from './types';

export interface TraceLane {
  id: string;
  name: string;
}

export const DEFAULT_LANES: TraceLane[] = [
  { id: 'merchant', name: 'Demo shop' },
  { id: 'railpay', name: 'Railpay (gateway)' },
  { id: 'railpay-acquiring', name: 'Railpay Acquiring' },
  { id: 'railnet', name: 'Railnet (network)' },
  { id: 'kestrel', name: 'Kestrel Bank (issuer)' },
];

export interface ArrowLayout {
  event: TraceEvent;
  label: string;
  fromName: string;
  toName: string;
  fromIndex: number;
  toIndex: number;
  direction: 'forward' | 'backward' | 'self';
  startPercent: number;
  endPercent: number;
  leftPercent: number;
  widthPercent: number;
  timeFormatted: string;
  isStandIn: boolean;
  mtiName?: string;
}

/** Formats an ISO date string to HH:mm:ss.SSS for event sequences. */
export function formatEventTime(isoString: string): string {
  if (!isoString) {
    return '';
  }
  try {
    const d = new Date(isoString);
    if (Number.isNaN(d.getTime())) {
      return isoString;
    }
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    const ms = String(d.getMilliseconds()).padStart(3, '0');
    return `${hh}:${mm}:${ss}.${ms}`;
  } catch {
    return isoString;
  }
}

/** Determines the lane index for a party id among configured lanes. */
export function getLaneIndex(partyId: string, lanes: TraceLane[] = DEFAULT_LANES): number {
  const index = lanes.findIndex((l) => l.id === partyId);
  return index >= 0 ? index : 0;
}

/** Constructs arrow layout metrics and display values for a single trace event. */
export function layoutEventArrow(
  event: TraceEvent,
  lanes: TraceLane[] = DEFAULT_LANES,
): ArrowLayout {
  const count = lanes.length;
  const fromIndex = getLaneIndex(event.from, lanes);
  const toIndex = getLaneIndex(event.to, lanes);

  let direction: 'forward' | 'backward' | 'self' = 'self';
  if (fromIndex < toIndex) {
    direction = 'forward';
  } else if (fromIndex > toIndex) {
    direction = 'backward';
  }

  const startPercent = ((fromIndex + 0.5) / count) * 100;
  const endPercent = ((toIndex + 0.5) / count) * 100;
  const leftPercent = Math.min(startPercent, endPercent);
  const widthPercent = Math.abs(endPercent - startPercent);

  let label = event.summary;
  if (event.kind === 'iso8583' && event.mti) {
    label = `${event.mti} ${event.summary}`;
  }

  const fromName = PARTY_NAMES[event.from] ?? event.from;
  const toName = PARTY_NAMES[event.to] ?? event.to;
  const timeFormatted = formatEventTime(event.at);
  const isStandIn = (event.summary ?? '').toLowerCase().includes('stands in');
  const mtiName = event.mti ? getMtiName(event.mti) : undefined;

  return {
    event,
    label,
    fromName,
    toName,
    fromIndex,
    toIndex,
    direction,
    startPercent,
    endPercent,
    leftPercent,
    widthPercent,
    timeFormatted,
    isStandIn,
    mtiName,
  };
}

/** Converts an array of trace events into arrow layout structures. */
export function layoutTraceArrows(
  events: TraceEvent[],
  lanes: TraceLane[] = DEFAULT_LANES,
): ArrowLayout[] {
  return events.map((event) => layoutEventArrow(event, lanes));
}
