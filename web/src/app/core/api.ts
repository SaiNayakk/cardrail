import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  CheckoutRequest,
  Payment,
  ShopConfig,
  TestCard,
  TraceDetail,
  TraceSummary,
} from './types';

/** Generates a new random order id between 8 and 64 alphanumeric characters. */
export function createOrderId(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const array = new Uint8Array(24);
  crypto.getRandomValues(array);
  let id = 'ord';
  for (const byte of array) {
    id += chars[byte % chars.length];
  }
  return id;
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  get<T>(path: string, params?: Record<string, string | number | boolean | undefined>): Promise<T> {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(params ?? {})) {
      if (v !== undefined) {
        p = p.set(k, String(v));
      }
    }
    return firstValueFrom(this.http.get<T>(path, { params: p }));
  }

  post<T>(path: string, body: unknown = {}): Promise<T> {
    return firstValueFrom(this.http.post<T>(path, body));
  }

  getShopConfig(): Promise<ShopConfig> {
    return this.get<ShopConfig>('/api/shop/v1/config');
  }

  checkout(req: CheckoutRequest): Promise<Payment> {
    return this.post<Payment>('/api/shop/v1/checkout', req);
  }

  getTestCards(): Promise<TestCard[]> {
    return this.get<TestCard[]>('/api/test-cards');
  }

  getRecentTraces(): Promise<TraceSummary[]> {
    return this.get<TraceSummary[]>('/api/trace/traces');
  }

  getTrace(reference: string): Promise<TraceDetail> {
    return this.get<TraceDetail>(`/api/trace/traces/${encodeURIComponent(reference)}`);
  }
}
