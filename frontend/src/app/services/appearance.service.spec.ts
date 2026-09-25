import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AppearanceService, normalizeTheme } from './appearance.service';
import { DashboardTheme } from '../models/appearance.model';

describe('AppearanceService', () => {
  let service: AppearanceService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('lumina.dashboardTheme');
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AppearanceService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => localStorage.removeItem('lumina.dashboardTheme'));

  it('startet ohne gespeicherten Wert dunkel und uebernimmt den Serverwert', fakeAsync(() => {
    const seen: DashboardTheme[] = [];
    const sub = service.dashboardTheme$.subscribe(t => seen.push(t));
    tick();
    http.expectOne('/api/v1/appearance').flush({ dashboardTheme: 'LIGHT' });

    expect(seen).toEqual(['DARK', 'LIGHT']);
    expect(localStorage.getItem('lumina.dashboardTheme')).toBe('LIGHT');
    sub.unsubscribe();
    discardPeriodicTasks();
  }));

  it('fragt minuetlich nach, damit das Wandtablet eine Umstellung bemerkt', fakeAsync(() => {
    const seen: DashboardTheme[] = [];
    const sub = service.dashboardTheme$.subscribe(t => seen.push(t));
    tick();
    http.expectOne('/api/v1/appearance').flush({ dashboardTheme: 'DARK' });
    tick(60_000);
    http.expectOne('/api/v1/appearance').flush({ dashboardTheme: 'LIGHT' });

    expect(seen).toEqual(['DARK', 'LIGHT']);
    sub.unsubscribe();
    discardPeriodicTasks();
  }));

  it('behaelt bei einem Fehler den letzten Stand', fakeAsync(() => {
    localStorage.setItem('lumina.dashboardTheme', 'LIGHT');
    service = TestBed.runInInjectionContext(() => new AppearanceService());
    const seen: DashboardTheme[] = [];
    const sub = service.dashboardTheme$.subscribe(t => seen.push(t));
    tick();
    http.expectOne('/api/v1/appearance').flush('kaputt', { status: 500, statusText: 'Server Error' });

    expect(seen).toEqual(['LIGHT']);
    sub.unsubscribe();
    discardPeriodicTasks();
  }));

  it('uebernimmt nach dem Speichern sofort den neuen Wert', () => {
    const seen: DashboardTheme[] = [];
    const sub = service.dashboardTheme$.subscribe(t => seen.push(t));
    http.match('/api/v1/appearance').forEach(r => r.flush({ dashboardTheme: 'DARK' }));

    service.save('LIGHT').subscribe();
    const put = http.expectOne(r => r.method === 'PUT');
    expect(put.request.body).toEqual({ dashboardTheme: 'LIGHT' });
    put.flush({ dashboardTheme: 'LIGHT' });

    expect(seen[seen.length - 1]).toBe('LIGHT');
    sub.unsubscribe();
  });

  it('wertet alles ausser LIGHT als dunkel', () => {
    expect(normalizeTheme('LIGHT')).toBe('LIGHT');
    expect(normalizeTheme('DARK')).toBe('DARK');
    expect(normalizeTheme('hell')).toBe('DARK');
    expect(normalizeTheme(null)).toBe('DARK');
  });
});
