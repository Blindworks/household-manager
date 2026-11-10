import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FinanceRecurringComponent } from './finance-recurring.component';

describe('FinanceRecurringComponent', () => {
  let fixture: ComponentFixture<FinanceRecurringComponent>;
  let http: HttpTestingController;

  const row = (id: number, confirmed: boolean, ended: boolean) => ({
    id, accountId: 1, counterpartyPattern: 'PAYPAL:SPOTIFY AB', expectedAmount: -9.99,
    interval: 'MONTHLY', nextDueDate: '2026-12-01', confirmed, lastSeenDate: '2026-11-01', ended
  });

  function flushInit(rows: unknown[]): void {
    http.expectOne(r => r.url.endsWith('/categories')).flush([]);
    http.expectOne(r => r.url.endsWith('/accounts')).flush([{ id: 1, name: 'Giro' }]);
    http.expectOne(r => r.url.endsWith('/recurring')).flush(rows);
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [FinanceRecurringComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FinanceRecurringComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('zeigt nach der Suche die Zahl neuer und aktualisierter Einträge', () => {
    flushInit([]);
    fixture.componentInstance.detect();
    http.expectOne(r => r.url.endsWith('/recurring/detect')).flush({ created: 2, updated: 5 });
    http.expectOne(r => r.url.endsWith('/recurring')).flush([]);
    expect(fixture.componentInstance.infoMessage).toBe('2 neue, 5 aktualisierte Einträge.');
  });

  it('hat die Spalte Letzte Zahlung und markiert beendete Zeilen', () => {
    flushInit([row(1, true, false), row(2, false, true)]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('thead')?.textContent).toContain('Letzte Zahlung');
    const rows = el.querySelectorAll('tbody tr');
    expect(rows[0].classList.contains('ended')).toBeFalse();
    expect(rows[1].classList.contains('ended')).toBeTrue();
    expect(rows[1].textContent).toContain('beendet');
    expect(rows[1].textContent).toContain('SPOTIFY AB (über PayPal)');
    expect(rows[1].textContent).toContain('Bestätigen');
  });
});
