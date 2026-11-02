import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ActivateCardRequest,
  CardholderAuthentication,
  CardholderCard,
  CardholderControls,
  CardholderDisputeRequest,
  CardholderSessionRequest,
  CardholderSessionResponse,
  CardholderTransaction,
  CreateCardRequest,
  CreateCardResponse,
  ReportLostRequest,
  ReportLostResponse,
  UpdateCardholderControlsRequest,
} from './types';

const STORAGE_KEY = 'cardrail_cardholder_token';

@Injectable({ providedIn: 'root' })
export class CardholderApi {
  private readonly http = inject(HttpClient);

  readonly token = signal<string | null>(this.readInitialToken());
  readonly isAuthenticated = computed(() => !!this.token());

  private readInitialToken(): string | null {
    if (typeof sessionStorage !== 'undefined') {
      try {
        return sessionStorage.getItem(STORAGE_KEY);
      } catch {
        return null;
      }
    }
    return null;
  }

  setToken(token: string | null): void {
    this.token.set(token);
    if (typeof sessionStorage !== 'undefined') {
      try {
        if (token) {
          sessionStorage.setItem(STORAGE_KEY, token);
        } else {
          sessionStorage.removeItem(STORAGE_KEY);
        }
      } catch {
        // Storage access may be restricted
      }
    }
  }

  clearSession(): void {
    this.setToken(null);
  }

  private buildHeaders(): HttpHeaders {
    let headers = new HttpHeaders();
    const t = this.token();
    if (t) {
      headers = headers.set('Authorization', `Bearer ${t}`);
    }
    return headers;
  }

  private async request<T>(
    method: 'GET' | 'POST' | 'PUT',
    path: string,
    body?: unknown,
  ): Promise<T> {
    const headers = this.buildHeaders();
    try {
      let obs$;
      if (method === 'GET') {
        obs$ = this.http.get<T>(path, { headers });
      } else if (method === 'PUT') {
        obs$ = this.http.put<T>(path, body ?? {}, { headers });
      } else {
        obs$ = this.http.post<T>(path, body ?? {}, { headers });
      }
      return await firstValueFrom(obs$);
    } catch (err: unknown) {
      if (err instanceof HttpErrorResponse && err.status === 401) {
        this.clearSession();
      }
      throw err;
    }
  }

  async createSession(req: CardholderSessionRequest): Promise<CardholderSessionResponse> {
    const res = await this.request<CardholderSessionResponse>(
      'POST',
      '/api/cardholder/sessions',
      req,
    );
    this.setToken(res.token);
    return res;
  }

  async createCard(req: CreateCardRequest): Promise<CreateCardResponse> {
    const res = await this.request<CreateCardResponse>(
      'POST',
      '/api/cardholder/cards',
      req,
    );
    if (res.session?.token) {
      this.setToken(res.session.token);
    }
    return res;
  }

  getCard(): Promise<CardholderCard> {
    return this.request<CardholderCard>('GET', '/api/cardholder/card');
  }

  getTransactions(): Promise<CardholderTransaction[]> {
    return this.request<CardholderTransaction[]>('GET', '/api/cardholder/transactions');
  }

  getAuthentications(): Promise<CardholderAuthentication[]> {
    return this.request<CardholderAuthentication[]>('GET', '/api/cardholder/authentications');
  }

  approveAuthentication(id: string, approve: boolean): Promise<void> {
    return this.request<void>(
      'POST',
      `/api/cardholder/authentications/${encodeURIComponent(id)}`,
      { approve },
    );
  }

  freezeCard(): Promise<CardholderCard> {
    return this.request<CardholderCard>('POST', '/api/cardholder/card/freeze');
  }

  unfreezeCard(): Promise<CardholderCard> {
    return this.request<CardholderCard>('POST', '/api/cardholder/card/unfreeze');
  }

  async reportLost(stolen: boolean): Promise<ReportLostResponse> {
    const res = await this.request<ReportLostResponse>(
      'POST',
      '/api/cardholder/card/report-lost',
      { stolen } as ReportLostRequest,
    );
    if (res.session?.token) {
      this.setToken(res.session.token);
    }
    return res;
  }

  activateCard(cvv2: string): Promise<void> {
    return this.request<void>(
      'POST',
      '/api/cardholder/card/activate',
      { cvv2 } as ActivateCardRequest,
    );
  }

  getControls(): Promise<CardholderControls> {
    return this.request<CardholderControls>('GET', '/api/cardholder/controls');
  }

  updateControls(controls: UpdateCardholderControlsRequest): Promise<CardholderControls> {
    return this.request<CardholderControls>('PUT', '/api/cardholder/controls', controls);
  }

  createDispute(req: CardholderDisputeRequest): Promise<void> {
    return this.request<void>('POST', '/api/cardholder/disputes', req);
  }
}
