import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MeterReadingsComponent } from './meter-readings.component';

describe('MeterReadingsComponent', () => {
  let fixture: ComponentFixture<MeterReadingsComponent>;
  let http: HttpTestingController;

  const url = '/api/v1/meter-readings';
  const readings = [
    { id: 2, meterType: 'ELECTRICITY', readingValue: 110, readingDate: '2026-11-13T09:15:30', latest: true },
    { id: 1, meterType: 'ELECTRICITY', readingValue: 100, readingDate: '2026-11-13T08:00:00', latest: false }
  ];

  const element = (): HTMLElement => fixture.nativeElement;
  const button = (text: string): HTMLButtonElement =>
    Array.from(element().querySelectorAll<HTMLButtonElement>('.reading-card button'))
      .find(candidate => candidate.textContent?.trim() === text)!;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MeterReadingsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    fixture = TestBed.createComponent(MeterReadingsComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne(url).flush(readings);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('bietet Korrigieren und Löschen nur an der aktuellen Ablesung an', () => {
    expect(element().querySelectorAll('.reading-card').length).toBe(2);
    expect(element().querySelectorAll('.reading-card__actions').length).toBe(1);
  });

  it('korrigiert das Datum und behält die Uhrzeit', () => {
    button('Korrigieren').click();
    fixture.detectChanges();
    fixture.componentInstance.correction.date = '2025-11-20';
    fixture.componentInstance.saveCorrection();

    const request = http.expectOne(`${url}/2`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.readingDate).toBe('2025-11-20T09:15:30');
    expect(request.request.body.meterType).toBe('ELECTRICITY');
    request.flush({ reading: readings[0], autoCreatedReadings: [] });
    http.expectOne(url).flush(readings);
    expect(fixture.componentInstance.action).toBeNull();
  });

  it('löscht erst nach Bestätigung', () => {
    button('Löschen').click();
    fixture.detectChanges();
    http.expectNone(`${url}/2`);

    button('Endgültig löschen').click();
    const request = http.expectOne(`${url}/2`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    http.expectOne(url).flush([]);
  });

  it('zeigt den Ablehnungsgrund des Backends', () => {
    button('Löschen').click();
    fixture.detectChanges();
    button('Endgültig löschen').click();
    http.expectOne(`${url}/2`).flush('Nur die aktuelle Ablesung', { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(element().querySelector('.correction__error')?.textContent).toContain('Nur die aktuelle Ablesung');
  });
});
