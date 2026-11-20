import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GroceryRunLogComponent } from './grocery-run-log.component';
import { GroceryRunTracker } from '../../../services/grocery-run-tracker.service';
import { GroceryRunLogDetail, GroceryRunLogSummary } from '../../../models/grocery.model';

const RUNS = '/api/v1/admin/groceries/runs';

const RUN: GroceryRunLogSummary = {
  id: 7, trigger: 'NIGHTLY', startedAt: '2026-11-20T04:00:00', finishedAt: '2026-11-20T04:05:12', ok: true,
  error: null, offers: 500, rawOffers: 600, products: 2, aiCalls: 3, estimateCalls: 0, inputTokens: 12000,
  outputTokens: 900, aiFailures: 0, deferred: 0, newJudgements: 3, deals: 1, sourceFailures: 1
};

const DETAIL: GroceryRunLogDetail = {
  run: RUN,
  sources: [
    { source: 'PENNY', ok: true, offers: 80, reason: null },
    { source: 'REWE', ok: false, offers: null, reason: 'Zugriff gesperrt' }
  ],
  products: [
    { productId: 1, name: 'Cola', candidates: 5, unjudged: 2, newJudgements: 2, aiCalls: 1, aiFailures: 0,
      deferred: false, error: null, matches: 1, maybes: 0, deals: 1, normalPrice: null, estimatedPrice: 1.99,
      baseUnit: 'LITER' },
    { productId: 2, name: 'Butter', candidates: 1, unjudged: 1, newJudgements: 1, aiCalls: 1, aiFailures: 0,
      deferred: false, error: null, matches: 0, maybes: 0, deals: 0, normalPrice: null, estimatedPrice: null,
      baseUnit: 'KILOGRAM' }
  ],
  judgements: [
    { productId: 1, productName: 'Cola', verdict: 'MATCH', reasonKind: null, reason: null,
      offerTitle: 'Cola Zero 1,5 l', chain: 'Penny', price: 0.86, basePrice: 0.573, baseUnit: 'LITER' },
    { productId: 1, productName: 'Cola', verdict: 'NO', reasonKind: null, reason: 'Cola-Mix, keine Cola',
      offerTitle: 'Cola-Mix', chain: 'Penny', price: 0.99, basePrice: null, baseUnit: null },
    { productId: 2, productName: 'Butter', verdict: 'NO', reasonKind: null, reason: 'Margarine',
      offerTitle: null, chain: null, price: null, basePrice: null, baseUnit: null }
  ],
  judgementsTruncated: false
};

describe('GroceryRunLogComponent', () => {
  let fixture: ComponentFixture<GroceryRunLogComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GroceryRunLogComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), GroceryRunTracker]
    }).compileComponents();
    fixture = TestBed.createComponent(GroceryRunLogComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  function flushRuns(runs: GroceryRunLogSummary[] = [RUN]): void {
    httpMock.expectOne(r => r.url === RUNS && r.params.get('limit') === '30').flush(runs);
    fixture.detectChanges();
  }

  function openRun(): void {
    (el.querySelector('.run-log__run') as HTMLElement).click();
    fixture.detectChanges();
    httpMock.expectOne(`${RUNS}/7`).flush(DETAIL);
    fixture.detectChanges();
  }

  it('listsRunsWithStatus', () => {
    flushRuns();
    const row = el.querySelector('.run-log__run')!;
    expect(row.textContent).toContain('Nachtlauf');
    expect(row.textContent).toContain('5 Min. 12 s');
    expect(row.querySelector('.run-log__badge--warn')?.textContent).toContain('1 Quelle gestört');
  });

  it('showsEmptyHint', () => {
    flushRuns([]);
    expect(el.textContent).toContain('Noch kein Lauf protokolliert.');
  });

  it('opensDetailWithSourcesProductsAndJudgements', () => {
    flushRuns();
    openRun();
    expect(el.textContent).toContain('Fehler: Zugriff gesperrt');
    expect(el.querySelectorAll('.run-log__product').length).toBe(2);
    expect(el.textContent).toContain('1,99 €/l (geschätzt)');
    expect(el.querySelectorAll('.run-log__judgement').length).toBe(3);
    expect(el.textContent).toContain('Angebot nicht mehr im Bestand');
    expect(el.textContent).toContain('Passt nicht (2)');
  });

  it('filtersJudgementsByVerdictAndProduct', () => {
    flushRuns();
    openRun();
    const chips = Array.from(el.querySelectorAll('.run-log__chip')) as HTMLButtonElement[];
    chips.find(c => c.textContent!.includes('Passt nicht'))!.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('.run-log__judgement').length).toBe(2);

    (el.querySelectorAll('.run-log__product')[1] as HTMLElement).click();
    fixture.detectChanges();
    const shown = el.querySelectorAll('.run-log__judgement');
    expect(shown.length).toBe(1);
    expect(shown[0].textContent).toContain('Margarine');
  });

  it('secondClickClosesTheDetail', () => {
    flushRuns();
    openRun();
    (el.querySelector('.run-log__run') as HTMLElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.run-log__detail')).toBeNull();
  });

  it('showsDetailError', () => {
    flushRuns();
    (el.querySelector('.run-log__run') as HTMLElement).click();
    fixture.detectChanges();
    httpMock.expectOne(`${RUNS}/7`).flush({ message: 'Lauf nicht gefunden' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(el.querySelector('.run-log__detail .run-log__error')?.textContent).toContain('Lauf nicht gefunden');
  });

  it('reloadsAfterATrackedRunFinished', () => {
    flushRuns([]);
    TestBed.inject(GroceryRunTracker).finished.next({
      running: false, startedAt: null, finishedAt: null, lastResult: null, lastError: null
    });
    flushRuns();
    expect(el.querySelectorAll('.run-log__run').length).toBe(1);
  });
});
