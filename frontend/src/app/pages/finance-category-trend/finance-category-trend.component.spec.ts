import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FinanceCategoryTrendComponent } from './finance-category-trend.component';
import { CategoryTrendResponse } from '../../models/finance.model';
import { startMonth } from './category-trend.util';

describe('FinanceCategoryTrendComponent', () => {
  let fixture: ComponentFixture<FinanceCategoryTrendComponent>;
  let http: HttpTestingController;

  const response = (months: string[]): CategoryTrendResponse => ({
    months,
    categories: [
      { categoryId: 2, categoryName: 'Miete', color: '#123456', amounts: months.map(() => 600), total: 600 * months.length },
      { categoryId: null, categoryName: 'Unkategorisiert', color: '#cfd8dc', amounts: months.map(() => 0), total: 0 }
    ]
  });

  const trendRequest = () => http.expectOne(r => r.url.endsWith('/analytics/category-trend'));

  function flushInit(data: CategoryTrendResponse): void {
    http.expectOne(r => r.url.endsWith('/accounts')).flush([]);
    trendRequest().flush(data);
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [FinanceCategoryTrendComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FinanceCategoryTrendComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('fragt standardmäßig zwölf Monate bis zum laufenden ab', () => {
    const c = fixture.componentInstance;
    http.expectOne(r => r.url.endsWith('/accounts')).flush([]);
    const req = trendRequest();
    expect(req.request.params.get('to')).toBe(c.currentMonth);
    expect(req.request.params.get('from')).toBe(startMonth(c.currentMonth, 12));
    expect(req.request.params.has('accountId')).toBeFalse();
    req.flush(response(['2026-11']));
  });

  it('zeigt je Kategorie eine Tabellenzeile mit Summe', () => {
    flushInit(response(['2026-09', '2026-10']));
    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Miete');
    expect(rows[0].textContent).toContain('1,200');
  });

  it('zeigt nach dem Antippen nur die gewählte Kategorie im Diagramm', () => {
    flushInit(response(['2026-09', '2026-10']));
    const c = fixture.componentInstance;
    (fixture.nativeElement.querySelector('tbody tr') as HTMLElement).click();
    fixture.detectChanges();
    const names = (c.chartOption as { series: { name: string }[] }).series.map(s => s.name);
    expect(names).toEqual(['Miete']);
    c.showAll();
    expect((c.chartOption as { series: unknown[] }).series.length).toBe(2);
  });

  it('bestellt beim Zeitraumwechsel den laufenden Abruf ab', () => {
    flushInit(response(['2026-11']));
    const c = fixture.componentInstance;
    c.setRange(6);
    const first = trendRequest();
    c.setRange(24);
    const second = trendRequest();
    expect(first.cancelled).toBeTrue();
    expect(second.request.params.get('from')).toBe(startMonth(c.currentMonth, 24));
    second.flush(response(['2026-11']));
  });

  it('meldet einen leeren Zeitraum', () => {
    flushInit({ months: ['2026-11'], categories: [] });
    expect(fixture.nativeElement.textContent).toContain('keine Ausgaben');
  });
});
