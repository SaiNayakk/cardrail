import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  CheckoutRequest,
  Dispute,
  DisputeEvidenceRequest,
  DisputeListResponse,
  IssuerDisputeRequest,
  IssuerDisputeResponse,
  PartyBook,
  Payment,
  PaymentListResponse,
  Payout,
  PayoutListResponse,
  Refund,
  RefundRequest,
  ShopConfig,
  TestCard,
  TraceDetail,
  TraceSummary,
  WebhookEvent,
} from './types';
export { createRefundRequestId } from './refund';

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

  confirmPayment(paymentId: string): Promise<Payment> {
    return this.post<Payment>(
      `/api/shop/v1/payments/${encodeURIComponent(paymentId)}/confirm`,
      {},
    );
  }

  getPayments(): Promise<PaymentListResponse> {
    return this.get<PaymentListResponse>('/api/shop/v1/payments');
  }

  refundPayment(paymentId: string, req: RefundRequest): Promise<Refund> {
    return this.post<Refund>(`/api/shop/v1/payments/${encodeURIComponent(paymentId)}/refund`, req);
  }

  getWebhooks(): Promise<WebhookEvent[]> {
    return this.get<WebhookEvent[]>('/api/shop/v1/webhooks');
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

  getPayouts(): Promise<PayoutListResponse> {
    return this.get<PayoutListResponse>('/api/shop/v1/payouts');
  }

  getDisputes(): Promise<DisputeListResponse> {
    return this.get<DisputeListResponse>('/api/shop/v1/disputes');
  }

  submitDisputeEvidence(disputeId: string, req: DisputeEvidenceRequest): Promise<Dispute> {
    return this.post<Dispute>(
      `/api/shop/v1/disputes/${encodeURIComponent(disputeId)}/evidence`,
      req,
    );
  }

  acceptDispute(disputeId: string): Promise<Dispute> {
    return this.post<Dispute>(
      `/api/shop/v1/disputes/${encodeURIComponent(disputeId)}/accept`,
      {},
    );
  }

  createIssuerDispute(req: IssuerDisputeRequest): Promise<IssuerDisputeResponse> {
    return this.post<IssuerDisputeResponse>('/api/issuer/disputes', req);
  }

  getPartyBook(party: string): Promise<PartyBook> {
    return this.get<PartyBook>(`/api/books/${encodeURIComponent(party)}`);
  }
}
