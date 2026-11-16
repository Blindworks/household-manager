import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  GroceryDeal, GroceryDealsView, GroceryOwner, GroceryPriceObservation, GroceryProduct,
  GroceryProductRequest, GrocerySummary, GrocerySettings, GroceryMarket, GroceryMarketRefresh,
  GrocerySourceStatus, GroceryRunStarted, GroceryRunStatus, GroceryChainStatus
} from '../models/grocery.model';

/** Service fuer die Supermarkt-Angebote-API. */
@Injectable({ providedIn: 'root' })
export class GroceryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/groceries';
  private readonly adminUrl = '/api/v1/admin/groceries';

  getProducts(owner: GroceryOwner): Observable<GroceryProduct[]> {
    return this.http.get<GroceryProduct[]>(`${this.baseUrl}/products`, { params: this.ownerParams(owner) })
      .pipe(catchError(this.handleError));
  }

  createProduct(request: GroceryProductRequest): Observable<GroceryProduct> {
    return this.http.post<GroceryProduct>(`${this.baseUrl}/products`, request)
      .pipe(catchError(this.handleError));
  }

  updateProduct(id: number, request: GroceryProductRequest): Observable<GroceryProduct> {
    return this.http.put<GroceryProduct>(`${this.baseUrl}/products/${id}`, request)
      .pipe(catchError(this.handleError));
  }

  deleteProduct(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/products/${id}`)
      .pipe(catchError(this.handleError));
  }

  getDeals(owner: GroceryOwner): Observable<GroceryDealsView> {
    return this.http.get<GroceryDealsView>(`${this.baseUrl}/deals`, { params: this.ownerParams(owner) })
      .pipe(catchError(this.handleError));
  }

  getProductOffers(id: number): Observable<GroceryDeal[]> {
    return this.http.get<GroceryDeal[]>(`${this.baseUrl}/products/${id}/offers`)
      .pipe(catchError(this.handleError));
  }

  getPriceHistory(id: number): Observable<GroceryPriceObservation[]> {
    return this.http.get<GroceryPriceObservation[]>(`${this.baseUrl}/products/${id}/price-history`)
      .pipe(catchError(this.handleError));
  }

  sendVerdict(productId: number, fingerprint: string, fits: boolean): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/products/${productId}/verdicts`, { fingerprint, fits })
      .pipe(catchError(this.handleError));
  }

  getSummary(): Observable<GrocerySummary> {
    return this.http.get<GrocerySummary>(`${this.baseUrl}/summary`)
      .pipe(catchError(this.handleError));
  }

  // ---- Admin (/v1/admin/groceries) ----

  getSettings(): Observable<GrocerySettings> {
    return this.http.get<GrocerySettings>(`${this.adminUrl}/settings`).pipe(catchError(this.handleError));
  }

  updateSettings(settings: GrocerySettings): Observable<GrocerySettings> {
    return this.http.put<GrocerySettings>(`${this.adminUrl}/settings`, settings).pipe(catchError(this.handleError));
  }

  getMarkets(): Observable<GroceryMarket[]> {
    return this.http.get<GroceryMarket[]>(`${this.adminUrl}/markets`).pipe(catchError(this.handleError));
  }

  refreshMarkets(): Observable<GroceryMarketRefresh> {
    return this.http.post<GroceryMarketRefresh>(`${this.adminUrl}/markets/refresh`, {})
      .pipe(catchError(this.handleError));
  }

  setMarketSelected(id: number, selected: boolean): Observable<GroceryMarket> {
    return this.http.put<GroceryMarket>(`${this.adminUrl}/markets/${id}`, { selected })
      .pipe(catchError(this.handleError));
  }

  getChains(): Observable<GroceryChainStatus[]> {
    return this.http.get<GroceryChainStatus[]>(`${this.adminUrl}/chains`).pipe(catchError(this.handleError));
  }

  setChainEnabled(chain: string, enabled: boolean): Observable<{ enabled: boolean }> {
    return this.http.put<{ enabled: boolean }>(`${this.adminUrl}/chains/${chain}`, { enabled })
      .pipe(catchError(this.handleError));
  }

  getSources(): Observable<GrocerySourceStatus[]> {
    return this.http.get<GrocerySourceStatus[]>(`${this.adminUrl}/sources`).pipe(catchError(this.handleError));
  }

  getRunStatus(): Observable<GroceryRunStatus> {
    return this.http.get<GroceryRunStatus>(`${this.adminUrl}/runs/status`).pipe(catchError(this.handleError));
  }

  /** Startet den Lauf im Hintergrund (202); das Ergebnis zeigt danach der Quellenstatus. */
  runNow(): Observable<GroceryRunStarted> {
    return this.http.post<GroceryRunStarted>(`${this.adminUrl}/runs`, {}).pipe(
      catchError((error: HttpErrorResponse) => error.status === 429
        ? throwError(() => new Error(error.error?.message || 'Bitte 10 Minuten warten.'))
        : this.handleError(error)));
  }

  private ownerParams(owner: GroceryOwner): HttpParams {
    return new HttpParams().set('owner', owner);
  }

  /** Die Server-Meldung (z. B. 400 bei Validierung) bleibt erhalten und wird angezeigt. */
  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Angebote-API-Fehler:', error);
    const message = error.error?.message || 'Die Angebote konnten nicht geladen werden.';
    return throwError(() => new Error(message));
  }
}
