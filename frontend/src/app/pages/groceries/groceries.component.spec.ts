import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { GroceriesComponent } from './groceries.component';
import { GroceryDeal, GroceryProduct, GroceryRunStatus } from '../../models/grocery.model';

const RUNS = '/api/v1/groceries/runs';
const NIGHT_RUN: GroceryRunStatus = { running: false, startedAt: '2026-11-17T04:00:00',
  finishedAt: '2026-11-17T04:06:00', lastResult: null, lastError: null };

registerLocaleData(localeDe);

function deal(overrides: Partial<GroceryDeal>): GroceryDeal {
  return {
    productId: 1, productName: 'Cola', ownerUserId: 5, chain: 'EDEKA', chainName: 'EDEKA',
    marketName: null, title: 'Coca-Cola', packaging: '1,5 l', price: 1.99, basePrice: 1.33,
    baseUnit: 'LITER', reference: 1.85, referenceKind: 'USUAL', savingPercent: 28, appPrice: null,
    appBasePrice: null, appOnly: false, verdict: 'MATCH', reasonKind: null, reason: null,
    userDecided: false, warmingUp: false, deal: true, validFrom: '2026-11-16', validTo: '2026-11-21',
    imageUrl: null, fingerprint: 'fp-1', ...overrides
  };
}

function product(overrides: Partial<GroceryProduct>): GroceryProduct {
  return {
    id: 1, ownerUserId: 5, ownerName: 'Ben', name: 'Cola', baseUnit: 'LITER', searchWords: ['cola'],
    mustWords: [], excludeWords: [], normalPricePerUnit: null, active: true, editable: true, ...overrides
  };
}

describe('GroceriesComponent', () => {
  let fixture: ComponentFixture<GroceriesComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GroceriesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(GroceriesComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  /** Startet die Seite und beantwortet die drei Anfangsabrufe (Nutzer, Schnaeppchen, Produkte). */
  function start(deals: GroceryDeal[], products: GroceryProduct[]): void {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/users').flush([]);
    flushDeals(deals);
    httpMock.expectOne(r => r.url === '/api/v1/groceries/products').flush(products);
    httpMock.expectOne(`${RUNS}/status`).flush(NIGHT_RUN);
    fixture.detectChanges();
  }

  function flushDeals(deals: GroceryDeal[]): TestRequest {
    const req = httpMock.expectOne(r => r.url === '/api/v1/groceries/deals');
    req.flush({ collectedAt: '2026-11-16T03:00:00', deals });
    return req;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function dealNames(): string[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[data-test="deal"]'))
      .map(el => el.querySelector('strong')?.textContent?.trim() ?? '');
  }

  it('rendersMatchBeforeMaybeWithReason', () => {
    start([
      deal({ productName: 'Vielleicht', verdict: 'MAYBE', reason: 'Sorte offen', fingerprint: 'a' }),
      deal({ productName: 'Sicher', fingerprint: 'b' })
    ], [product({ id: 1 })]);

    expect(dealNames()).toEqual(['Sicher', 'Vielleicht']);
    expect(text()).toContain('passt vielleicht: Sorte offen');
  });

  it('maybeShowsConfirmButtonMatchDoesNot', () => {
    start([deal({ verdict: 'MATCH' })], [product({})]);
    expect(fixture.nativeElement.querySelector('[data-test="confirm-fit"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-test="reject"]')).not.toBeNull();
  });

  it('maybeShowsConfirmButton', () => {
    start([deal({ verdict: 'MAYBE', reason: 'Sorte offen' })], [product({})]);
    expect(fixture.nativeElement.querySelector('[data-test="confirm-fit"]')).not.toBeNull();
  });

  it('rejectRemovesDealAfterSuccess', () => {
    start([deal({})], [product({})]);

    (fixture.nativeElement.querySelector('[data-test="reject"]') as HTMLButtonElement).click();
    const verdict = httpMock.expectOne('/api/v1/groceries/products/1/verdicts');
    expect(verdict.request.body).toEqual({ fingerprint: 'fp-1', fits: false });
    verdict.flush(null);
    flushDeals([]);
    fixture.detectChanges();

    expect(dealNames()).toEqual([]);
  });

  it('foreignProductsAreNotEditable', () => {
    start([deal({})], [product({ editable: false })]);

    expect(fixture.nativeElement.querySelector('[data-test="reject"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-test="edit"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-test="delete"]')).toBeNull();
  });

  it('warmingUpShowsHint', () => {
    start([deal({ warmingUp: true })], [product({})]);
    expect(text()).toContain('noch wenig Vergleichsdaten');
  });

  it('appOnlyShowsHint', () => {
    start([deal({ appOnly: true, appBasePrice: 1.25 })], [product({})]);
    expect(text()).toContain('nur mit App');
    expect(text()).toContain('mit App 1,25 €/l');
  });

  it('showsMarketOrAllMarketsAndSaving', () => {
    start([deal({ marketName: null })], [product({})]);
    expect(text()).toContain('alle Märkte');
    expect(text()).toContain('−28 %');
    expect(text()).toContain('üblich 1,85 €/l');
  });

  it('loadsWithOwnerWhenSelectionChanges', () => {
    start([], []);

    fixture.componentInstance.onOwnerChange('household');
    const deals = httpMock.expectOne(r => r.url === '/api/v1/groceries/deals');
    expect(deals.request.params.get('owner')).toBe('household');
    deals.flush({ collectedAt: null, deals: [] });
    const products = httpMock.expectOne(r => r.url === '/api/v1/groceries/products');
    expect(products.request.params.get('owner')).toBe('household');
    products.flush([]);
  });

  it('searchNowStartsRunAndReloadsWhenItFinished', fakeAsync(() => {
    start([], []);
    const el = fixture.nativeElement as HTMLElement;
    const button = () => el.querySelector('[data-test="search-now"]') as HTMLButtonElement;

    button().click();
    const post = httpMock.expectOne(RUNS);
    expect(post.request.method).toBe('POST');
    post.flush({ status: 'gestartet' }, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
    expect(button().disabled).toBeTrue();
    expect(button().textContent).toContain('Suche läuft');

    tick(10000);
    httpMock.expectOne(`${RUNS}/status`).flush({ ...NIGHT_RUN, finishedAt: '2026-11-17T20:15:00',
      lastResult: { offers: 640, aiCalls: 3, deferred: 0, deals: 2, aiFailures: 0, sourceFailures: {} } });
    flushDeals([deal({})]);
    httpMock.expectOne(r => r.url === '/api/v1/groceries/products').flush([product({})]);
    fixture.detectChanges();

    expect(button().disabled).toBeFalse();
    expect(el.querySelector('[data-test="search-message"]')?.textContent).toContain('Suche fertig: 640 Angebote');
    expect(el.querySelectorAll('[data-test="deal"]').length).toBe(1);
  }));

  it('searchNowShowsCooldownMessage', () => {
    start([], []);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[data-test="search-now"]') as HTMLButtonElement).click();
    httpMock.expectOne(RUNS).flush({ message: 'Bitte 10 Minuten warten.' },
      { status: 429, statusText: 'Too Many Requests' });
    fixture.detectChanges();

    expect(el.querySelector('[data-test="search-message"]')?.textContent).toContain('Bitte 10 Minuten warten.');
    expect((el.querySelector('[data-test="search-now"]') as HTMLButtonElement).disabled).toBeFalse();
  });

  it('updatesProductWithEmptySearchWordsForServerSuggestion', () => {
    start([], [product({})]);
    const component = fixture.componentInstance;
    component.openEdit(product({}));
    component.form!.searchWords = '  ';

    component.saveForm();
    const req = httpMock.expectOne('/api/v1/groceries/products/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.searchWords).toBeNull();
    req.flush(product({}));
    flushDeals([]);
    httpMock.expectOne(r => r.url === '/api/v1/groceries/products').flush([]);
  });

  it('createsProductWithEmptySearchWordsForServerSuggestion', () => {
    start([], []);
    const component = fixture.componentInstance;
    component.openCreate();
    component.form!.name = 'Butter';

    component.saveForm();
    const req = httpMock.expectOne('/api/v1/groceries/products');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.searchWords).toBeNull();
    expect(req.request.body.name).toBe('Butter');
    req.flush(product({ id: 2, name: 'Butter' }));
    flushDeals([]);
    httpMock.expectOne(r => r.url === '/api/v1/groceries/products').flush([]);
  });

  it('showsServerMessageWhenSavingFails', () => {
    start([], []);
    const component = fixture.componentInstance;
    component.openCreate();
    component.form!.name = 'Butter';

    component.saveForm();
    httpMock.expectOne('/api/v1/groceries/products')
      .flush({ message: 'Name schon vergeben' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(text()).toContain('Name schon vergeben');
  });
});
