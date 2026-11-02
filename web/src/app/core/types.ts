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

export interface NextAction {
  type: string;
  url: string;
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
    | 'pending'
    | 'requires_action';
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
  nextAction?: NextAction;
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

export interface Payout {
  id: string;
  object: 'payout';
  amount: number;
  sales: number;
  refunds: number;
  fees: number;
  disputes: number;
  createdAt: string;
}

export interface PayoutListResponse {
  object: 'list';
  data: Payout[];
}

export type DisputeStatus = 'needs_response' | 'under_review' | 'won' | 'lost';

export interface Dispute {
  id: string;
  object: 'dispute';
  payment: string;
  amount: number;
  reason: string;
  reasonCode: string;
  status: DisputeStatus;
  createdAt: string;
}

export interface DisputeListResponse {
  object: 'list';
  data: Dispute[];
}

export interface DisputeEvidenceRequest {
  evidence: string;
}

export interface IssuerDisputeRequest {
  reference: string;
  reason: string;
}

export interface IssuerDisputeResponse {
  id: string;
  rrn: string;
  amountCents: number;
  reasonCode: string;
  status: string;
}

export interface PartyBook {
  party: string;
  total: number;
  accounts: Record<string, number>;
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

export interface CardholderSessionRequest {
  number: string;
  expiry: string;
  cvv2: string;
}

export interface CardholderSessionResponse {
  token: string;
  cardId: string;
  expiresAt: string;
}

export interface CreateCardRequest {
  kind: 'DEBIT' | 'CREDIT';
}

export interface IssuedCardDetails {
  cardId: string;
  accountId: string;
  pan: string;
  expiry: string;
  cvv2: string;
}

export interface CreateCardResponse {
  card: IssuedCardDetails;
  session: CardholderSessionResponse;
}

export type CardholderCardStatus = 'ACTIVE' | 'INACTIVE' | 'BLOCKED' | 'LOST' | 'STOLEN';

export interface CardholderCard {
  id: string;
  last4: string;
  kind: 'DEBIT' | 'CREDIT';
  status: CardholderCardStatus;
  expiry: string;
  shared: boolean;
  balanceCents: number;
  availableCents: number;
  heldCents: number;
  creditLimitCents: number;
}

export type CardholderTransactionStatus =
  | 'APPROVED'
  | 'DECLINED'
  | 'REVERSED'
  | 'CLEARED'
  | 'EXPIRED';

export interface CardholderTransaction {
  reference: string;
  kind: 'PURCHASE' | 'REFUND';
  merchant: string;
  amountCents: number;
  status: CardholderTransactionStatus;
  responseCode?: string;
  responseText?: string;
  fraudScore?: number;
  riskReasons?: string;
  authenticated: boolean;
  disputable: boolean;
  at: string;
}

export interface CardholderAuthentication {
  id: string;
  amountCents: number;
  merchant: string;
  otp: string;
  expiresAt: string;
}

export interface CardholderControls {
  onlineEnabled: boolean;
  singleLimitCents: number | null;
  dailyLimitCents: number | null;
  blockedMccs: string[];
  stepUpAlways?: boolean;
  shared?: boolean;
}

export interface UpdateCardholderControlsRequest {
  onlineEnabled: boolean;
  singleLimitCents: number | null;
  dailyLimitCents: number | null;
  blockedMccs: string[];
}

export interface CardholderDisputeRequest {
  reference: string;
  reason: '1040' | '1310' | '1330' | '1240' | string;
}

export interface ReportLostRequest {
  stolen: boolean;
}

export interface ReportLostResponse {
  card: IssuedCardDetails;
  session: CardholderSessionResponse;
}

export interface ActivateCardRequest {
  cvv2: string;
}
