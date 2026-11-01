import { signal } from '@angular/core';
import { messageOf } from './problem';

/** One resource a page loads: loads on creation, exposes signals for state, and can be reloaded. */
export class Loader<T> {
  readonly value = signal<T | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  constructor(
    private readonly fetcher: () => Promise<T>,
    loadImmediate = true,
  ) {
    if (loadImmediate) {
      void this.reload();
    }
  }

  async reload(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await this.fetcher();
      this.value.set(data);
      this.error.set('');
    } catch (e: unknown) {
      this.error.set(messageOf(e));
    } finally {
      this.loading.set(false);
    }
  }

  set(val: T): void {
    this.value.set(val);
    this.loading.set(false);
    this.error.set('');
  }
}
