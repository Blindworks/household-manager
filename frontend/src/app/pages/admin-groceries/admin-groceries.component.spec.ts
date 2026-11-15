import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminGroceriesComponent } from './admin-groceries.component';
import { GroceryMarket } from '../../models/grocery.model';

const BASE = '/api/v1/admin/groceries';

const MARKET: GroceryMarket = {
  id: 7, chain: 'REWE', externalId: '1', name: 'REWE Musterstraße', address: 'Musterstraße 1, 60311 Frankfurt',
  lat: 50.1, lng: 8.6, selected: false
};

describe('AdminGroceriesComponent', () => {
  let fixture: ComponentFixture<AdminGroceriesComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  function flushInitial(markets: GroceryMarket[] = [MARKET]): void {
    httpMock.expectOne(`${BASE}/settings`).flush({ zip: '60311', lat: 50.1, lng: 8.6, dealThresholdPercent: 10 });
    httpMock.expectOne(`${BASE}/markets`).flush(markets);
    httpMock.expectOne(`${BASE}/chains`).flush([{ chain: 'LIDL', name: 'Lidl', enabled: true }]);
    httpMock.expectOne(`${BASE}/sources`).flush([
      { source: 'MARKTGURU', enabled: true, lastRunAt: '2026-11-15T03:00:00', offerCount: 120, ok: true, reason: null },
      { source: 'REWE', enabled: true, lastRunAt: '2026-11-15T03:00:00', offerCount: 0, ok: false, reason: 'HTTP 500' }
    ]);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminGroceriesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminGroceriesComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  it('showsSourceStatusWithReason', () => {
    flushInitial();
    const rows = el.querySelectorAll('.admin-groceries__source');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Marktguru');
    expect(rows[0].textContent).toContain('OK');
    expect(rows[1].textContent).toContain('Fehler: HTTP 500');
  });

  it('selectingMarketCallsPut', () => {
    flushInitial();
    const checkbox = el.querySelector('.admin-groceries__market input') as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));

    const req = httpMock.expectOne(`${BASE}/markets/7`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ selected: true });
    req.flush({ ...MARKET, selected: true });
    fixture.detectChanges();
    expect((el.querySelector('.admin-groceries__market input') as HTMLInputElement).checked).toBeTrue();
  });

  it('refreshMarketsReloadsList', () => {
    flushInitial([]);
    const button = Array.from(el.querySelectorAll('button')).find(b => b.textContent?.includes('Märkte suchen'))!;
    button.click();

    const req = httpMock.expectOne(`${BASE}/markets/refresh`);
    expect(req.request.method).toBe('POST');
    req.flush({ markets: [MARKET], failedChains: ['REWE'] });
    fixture.detectChanges();

    expect(el.textContent).toContain('REWE Musterstraße');
    expect(el.textContent).toContain('Für REWE konnte keine Marktliste geladen werden.');
  });

  it('runNowShows429Message', () => {
    flushInitial();
    (el.querySelector('.admin-groceries__run') as HTMLButtonElement).click();

    httpMock.expectOne(`${BASE}/runs`).flush({ message: 'Bitte 10 Minuten warten.' },
      { status: 429, statusText: 'Too Many Requests' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-groceries__run-message')?.textContent).toContain('Bitte 10 Minuten warten.');
  });

  it('runNowPollsSourcesUntilARunIsNewerAndShowsTheResult', fakeAsync(() => {
    flushInitial();
    (el.querySelector('.admin-groceries__run') as HTMLButtonElement).click();

    httpMock.expectOne(`${BASE}/runs`).flush({ status: 'gestartet' }, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
    expect(el.querySelector('.admin-groceries__run-message')?.textContent).toContain('Suche läuft');
    expect((el.querySelector('.admin-groceries__run') as HTMLButtonElement).disabled).toBeTrue();

    tick(10000);
    httpMock.expectOne(`${BASE}/sources`).flush([
      { source: 'MARKTGURU', enabled: true, lastRunAt: '2026-11-15T03:00:00', offerCount: 120, ok: true, reason: null }
    ]);
    fixture.detectChanges();
    expect(el.querySelector('.admin-groceries__run-message')?.textContent).toContain('Suche läuft');

    tick(10000);
    httpMock.expectOne(`${BASE}/sources`).flush([
      { source: 'MARKTGURU', enabled: true, lastRunAt: '2026-11-15T20:31:00', offerCount: 130, ok: true, reason: null },
      { source: 'REWE', enabled: true, lastRunAt: '2026-11-15T20:31:05', offerCount: 0, ok: false, reason: 'HTTP 500' }
    ]);
    fixture.detectChanges();

    const message = el.querySelector('.admin-groceries__run-message')?.textContent ?? '';
    expect(message).toContain('Suche fertig: 130 Angebote');
    expect(message).toContain('1 Quelle gestört');
    expect((el.querySelector('.admin-groceries__run') as HTMLButtonElement).disabled).toBeFalse();
    tick(30000);
    httpMock.expectNone(`${BASE}/sources`);
  }));

  it('pollingStopsOnDestroy', fakeAsync(() => {
    flushInitial();
    (el.querySelector('.admin-groceries__run') as HTMLButtonElement).click();
    httpMock.expectOne(`${BASE}/runs`).flush({ status: 'gestartet' }, { status: 202, statusText: 'Accepted' });

    fixture.destroy();
    tick(30000);

    httpMock.expectNone(`${BASE}/sources`);
    discardPeriodicTasks();
  }));

  it('blocksSavingOutsideThresholdRange', () => {
    flushInitial();
    fixture.componentInstance.threshold = 0;
    expect(fixture.componentInstance.thresholdValid).toBeFalse();
    fixture.componentInstance.threshold = 51;
    expect(fixture.componentInstance.thresholdValid).toBeFalse();
    fixture.componentInstance.threshold = 50;
    expect(fixture.componentInstance.thresholdValid).toBeTrue();
  });
});
