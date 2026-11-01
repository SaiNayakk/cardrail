export interface Product {
  id: string;
  name: string;
  priceCents: number;
}

export interface ShopConfig {
  publishableKey: string;
  products: Product[];
}

export interface CheckoutRequest {
  product: string;
  quantity: number;
  token: string;
  orderId: string;
}

export interface PaymentCard {
  brand: string;
  last4: string;
}

export interface Refund {
  id: string;
  object: 'refund';
  amount: number;
  status: 'pending' | 'succeeded' | 'failed';
  reference: string;
  failureReason?: string;
  payment: string;
  createdAt: string;
}

export interface Payment {
  id: string;
  object: 'payment';
  amount: number;
  currency: string;
  status:
    | 'captured'
    | 'authorised'
    | 'declined'
    | 'unknown'
    | 'voided'
    | 'reversed'
    | 'failed'
    | 'pending';
  capture?: boolean;
  amountCaptured?: number;
  amountRefunded?: number;
  refunds?: Refund[];
  card: PaymentCard;
  reference: string;
  authCode?: string;
  responseCode?: string;
  declineReason?: string;
  description?: string;
  createdAt: string;
}

export interface PaymentListResponse {
  object: 'list';
  data: Payment[];
}

export interface RefundRequest {
  amount?: number;
  requestId: string;
}

export interface WebhookEvent {
  id: string;
  object: 'event';
  type: string;
  createdAt: string;
  data: Payment | Refund | Record<string, unknown>;
}

export interface Problem {
  status?: number;
  code?: string;
  title?: string;
  detail?: string;
}

export interface TestCard {
  number: string;
  expiry: string;
  cvv2: string;
  kind: string;
  outcome: string;
}

export interface TraceSummary {
  traceKey: string;
  firstSummary: string;
  startedAt: string;
  events: number;
}

export interface TraceEvent {
  id: number;
  traceKey: string;
  reporter: string;
  from: string;
  to: string;
  kind: 'iso8583' | 'note' | string;
  mti?: string;
  summary: string;
  details?: Record<string, string>;
  at: string;
}

export interface TraceDetail {
  traceKey: string;
  events: TraceEvent[];
}

export type PartyId = 'merchant' | 'railpay' | 'railpay-acquiring' | 'railnet' | 'kestrel' | 'meridian';

export const PARTY_NAMES: Record<string, string> = {
  merchant: 'Demo shop',
  railpay: 'Railpay (gateway)',
  'railpay-acquiring': 'Railpay Acquiring',
  railnet: 'Railnet (network)',
  kestrel: 'Kestrel Bank (issuer)',
  meridian: 'Meridian',
};
