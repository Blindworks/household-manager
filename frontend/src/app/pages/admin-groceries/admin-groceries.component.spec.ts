import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminGroceriesComponent } from './admin-groceries.component';
import { GroceryMarket, GroceryRunResult, GroceryRunStatus } from '../../models/grocery.model';

const BASE = '/api/v1/admin/groceries';
const RUNS = '/api/v1/groceries/runs';

const MARKET: GroceryMarket = {
  id: 7, chain: 'REWE', externalId: '1', name: 'REWE Musterstraße', address: 'Musterstraße 1, 60311 Frankfurt',
  lat: 50.1, lng: 8.6, selected: false
};

describe('AdminGroceriesComponent', () => {
  let fixture: ComponentFixture<AdminGroceriesComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  const NIGHT = '2026-11-15T04:10:00';
  const RESULT: GroceryRunResult = { offers: 512, aiCalls: 4, deferred: 0, deals: 3, aiFailures: 1,
    sourceFailures: { REWE: 'HTTP 500' } };

  function status(over: Partial<GroceryRunStatus> = {}): GroceryRunStatus {
    return { running: false, startedAt: '2026-11-15T04:00:00', finishedAt: NIGHT, lastResult: RESULT,
      lastError: null, ...over };
  }

  function flushInitial(markets: GroceryMarket[] = [MARKET], runStatus: GroceryRunStatus | 'fail' = status()): void {
    httpMock.expectOne(`${BASE}/settings`).flush({ zip: '60311', lat: 50.1, lng: 8.6, dealThresholdPercent: 10 });
    httpMock.expectOne(`${BASE}/markets`).flush(markets);
    httpMock.expectOne(`${BASE}/chains`).flush([{ chain: 'LIDL', name: 'Lidl', enabled: true }]);
    httpMock.expectOne(`${BASE}/sources`).flush([
      { source: 'MARKTGURU', enabled: true, lastRunAt: '2026-11-15T03:00:00', offerCount: 120, ok: true, reason: null },
      { source: 'REWE', enabled: true, lastRunAt: '2026-11-15T03:00:00', offerCount: 0, ok: false, reason: 'HTTP 500' }
    ]);
    const statusRequest = httpMock.expectOne(`${RUNS}/status`);
    if (runStatus === 'fail') {
      statusRequest.flush({ message: 'kaputt' }, { status: 500, statusText: 'Server Error' });
    } else {
      statusRequest.flush(runStatus);
    }
    fixture.detectChanges();
  }

  function clickRun(): void {
    (el.querySelector('.admin-groceries__run') as HTMLButtonElement).click();
    httpMock.expectOne(RUNS).flush({ status: 'gestartet' }, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
  }

  function message(): string {
    return el.querySelector('.admin-groceries__run-message')?.textContent ?? '';
  }

  function runButton(): HTMLButtonElement {
    return el.querySelector('.admin-groceries__run') as HTMLButtonElement;
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

  it('disabledSourceWithOldErrorIsNotShownAsFailed', () => {
    httpMock.expectOne(`${BASE}/settings`).flush({ zip: '60311', lat: 50.1, lng: 8.6, dealThresholdPercent: 10 });
    httpMock.expectOne(`${BASE}/markets`).flush([MARKET]);
    httpMock.expectOne(`${BASE}/chains`).flush([]);
    httpMock.expectOne(`${BASE}/sources`).flush([
      { source: 'REWE', enabled: false, lastRunAt: '2026-11-16T07:51:00', offerCount: 0, ok: false,
        reason: 'Zugriff gesperrt (HTTP 403)' }
    ]);
    httpMock.expectOne(`${RUNS}/status`).flush(status());
    fixture.detectChanges();

    const row = el.querySelector('.admin-groceries__source') as HTMLElement;
    expect(row.textContent).toContain('Deaktiviert');
    expect(row.classList).not.toContain('admin-groceries__source--failed');
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

    httpMock.expectOne(RUNS).flush({ message: 'Bitte 10 Minuten warten.' },
      { status: 429, statusText: 'Too Many Requests' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-groceries__run-message')?.textContent).toContain('Bitte 10 Minuten warten.');
  });

  it('runFinishesOnlyWhenServerSaysNotRunningWithNewFinishedAt', fakeAsync(() => {
    flushInitial();
    clickRun();
    expect(message()).toContain('Suche läuft');
    expect(runButton().disabled).toBeTrue();

    // erste Quelle ist fertig, der Lauf aber nicht: das darf nicht als fertig gelten
    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush(status({ running: true, startedAt: '2026-11-16T20:30:00' }));
    fixture.detectChanges();
    expect(message()).toContain('Suche läuft');

    // alter Stand ohne laufenden Lauf (noch der Nachtlauf) gilt ebenfalls nicht
    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush(status());
    fixture.detectChanges();
    expect(message()).toContain('Suche läuft');

    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush(status({ finishedAt: '2026-11-16T20:34:00',
      lastResult: { ...RESULT, offers: 640, deals: 5 } }));
    httpMock.expectOne(`${BASE}/sources`).flush([]);
    fixture.detectChanges();

    expect(message()).toContain('Suche fertig: 640 Angebote');
    expect(message()).toContain('5 Schnäppchen');
    expect(message()).toContain('1 Claude-Bewertungen fehlgeschlagen');
    expect(message()).toContain('REWE (HTTP 500)');
    expect(runButton().disabled).toBeFalse();
    tick(30000);
    httpMock.expectNone(`${RUNS}/status`);
  }));

  it('showsTheErrorOfAFailedRun', fakeAsync(() => {
    flushInitial();
    clickRun();

    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush(status({ finishedAt: '2026-11-16T20:34:00', lastResult: null,
      lastError: 'DB weg' }));
    httpMock.expectOne(`${BASE}/sources`).flush([]);
    fixture.detectChanges();

    expect(message()).toContain('Suche fehlgeschlagen: DB weg');
  }));

  it('pageLoadWhileRunningShowsRunningAndPolls', fakeAsync(() => {
    flushInitial([MARKET], status({ running: true, startedAt: '2026-11-16T04:00:00' }));
    expect(message()).toContain('Suche läuft');
    expect(runButton().disabled).toBeTrue();

    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush(status({ finishedAt: '2026-11-16T04:06:00' }));
    httpMock.expectOne(`${BASE}/sources`).flush([]);
    fixture.detectChanges();

    expect(message()).toContain('Suche fertig: 512 Angebote');
    expect(runButton().disabled).toBeFalse();
  }));

  it('failedStatusRequestsDoNotStopPolling', fakeAsync(() => {
    flushInitial([MARKET], 'fail');
    expect(runButton().disabled).toBeFalse();
    clickRun();

    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush({ message: 'weg' }, { status: 502, statusText: 'Bad Gateway' });
    tick(10000);
    // ohne bekannten Ausgangsstand zählt ein altes Ende nicht, erst nach gesehenem Lauf
    httpMock.expectOne(`${RUNS}/status`).flush(status());
    fixture.detectChanges();
    expect(message()).toContain('Suche läuft');
    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush(status({ running: true }));
    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush(status({ finishedAt: '2026-11-16T20:40:00' }));
    httpMock.expectOne(`${BASE}/sources`).flush([]);
    fixture.detectChanges();

    expect(message()).toContain('Suche fertig');
  }));

  it('pollingStopsOnDestroy', fakeAsync(() => {
    flushInitial();
    clickRun();

    fixture.destroy();
    tick(30000);

    httpMock.expectNone(`${RUNS}/status`);
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
