import { TraceEvent } from './types';

/** Two minutes in milliseconds. */
export const STREAM_RECONNECT_MAX_AGE_MS = 2 * 60 * 1000;

/** Merges incoming trace events into an existing list, de-duplicating by unique id and preserving order. */
export function mergeTraceEvents(
  existing: TraceEvent[],
  incoming: TraceEvent | TraceEvent[],
): TraceEvent[] {
  const items = Array.isArray(incoming) ? incoming : [incoming];
  if (items.length === 0) {
    return existing;
  }

  const map = new Map<number, TraceEvent>();
  for (const ev of existing) {
    map.set(ev.id, ev);
  }
  for (const ev of items) {
    map.set(ev.id, ev);
  }

  // In the order the parties sent them. Reports reach the trace service in the background, so the order they
  // arrived in (their ids) can differ; the id only breaks ties.
  return Array.from(map.values()).sort((a, b) => {
    const byTime = new Date(a.at).getTime() - new Date(b.at).getTime();
    return byTime !== 0 ? byTime : a.id - b.id;
  });
}

/** Determines whether a closed stream should reconnect based on the timestamp of the last observed event. */
export function shouldReconnect(
  lastEventAt: string | null | undefined,
  nowMs: number = Date.now(),
): boolean {
  if (!lastEventAt) {
    // If no events have been received yet, allow reconnection
    return true;
  }
  const eventTime = new Date(lastEventAt).getTime();
  if (Number.isNaN(eventTime)) {
    return false;
  }
  const age = nowMs - eventTime;
  return age < STREAM_RECONNECT_MAX_AGE_MS;
}

export type StreamState = 'connecting' | 'connected' | 'closed' | 'error';

/** Manages a Server-Sent Events connection for live payment trace updates. */
export class TraceStream {
  private eventSource: EventSource | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private isDestroyed = false;

  constructor(
    private readonly reference: string,
    private readonly onEvent: (event: TraceEvent) => void,
    private readonly onStateChange?: (state: StreamState) => void,
  ) {
    this.connect();
  }

  private connect(): void {
    if (this.isDestroyed) {
      return;
    }

    this.onStateChange?.('connecting');
    const url = `/api/trace/traces/${encodeURIComponent(this.reference)}/stream`;
    this.eventSource = new EventSource(url);

    this.eventSource.onopen = () => {
      if (!this.isDestroyed) {
        this.onStateChange?.('connected');
      }
    };

    // The stream emits named "event" SSE messages
    this.eventSource.addEventListener('event', (msg: MessageEvent) => {
      if (this.isDestroyed) {
        return;
      }
      try {
        const parsed = JSON.parse(msg.data) as TraceEvent;
        this.onEvent(parsed);
      } catch {
        // Ignore unparseable frames
      }
    });

    this.eventSource.onerror = () => {
      this.cleanupSource();
      if (this.isDestroyed) {
        return;
      }
      this.onStateChange?.('closed');
    };
  }

  /** Checks if reconnection should occur and initiates it if criteria are met. */
  maybeReconnect(lastEventAt: string | null | undefined): void {
    if (this.isDestroyed || this.reconnectTimer !== null) {
      return;
    }

    if (shouldReconnect(lastEventAt)) {
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        this.connect();
      }, 1500);
    } else {
      this.onStateChange?.('closed');
    }
  }

  private cleanupSource(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  destroy(): void {
    this.isDestroyed = true;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.cleanupSource();
    this.onStateChange?.('closed');
  }
}
