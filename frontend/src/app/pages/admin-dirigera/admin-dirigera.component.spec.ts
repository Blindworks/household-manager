import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { AdminDirigeraComponent } from './admin-dirigera.component';
import { DirigeraHub, DirigeraPairingStatus, DirigeraSensor } from '../../models/dirigera.model';

registerLocaleData(localeDe);

const BASE = '/api/v1/dirigera';

const HUB: DirigeraHub = {
  host: '192.168.1.50', paired: false, hubId: null, lastSuccessAt: null, lastError: null
};
const IDLE: DirigeraPairingStatus = { state: 'IDLE', expiresAt: null, error: null };
const SENSOR: DirigeraSensor = {
  id: 1, kind: 'ENVIRONMENT', name: 'Wohnzimmer', room: 'Wohnzimmer', model: 'TIMMERFLOTTE', temperature: 21.5,
  humidity: 48, open: null, batteryPercent: 90, reachable: true, knownToHub: true, lastSeenAt: '2026-10-30T10:00:00'
};
const GONE: DirigeraSensor = { ...SENSOR, id: 2, name: 'Alt', knownToHub: false };
const CONTACT: DirigeraSensor = {
  ...SENSOR, id: 3, kind: 'CONTACT', name: 'Badfenster', model: 'PARASOLL', temperature: null, humidity: null, open: true
};

describe('AdminDirigeraComponent', () => {
  let fixture: ComponentFixture<AdminDirigeraComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminDirigeraComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminDirigeraComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  function initWith(sensors: DirigeraSensor[], pairing: DirigeraPairingStatus = IDLE, hub: DirigeraHub = HUB): void {
    fixture.detectChanges();
    httpMock.expectOne(`${BASE}/hub`).flush(hub);
    httpMock.expectOne(`${BASE}/sensors`).flush(sensors);
    httpMock.expectOne(`${BASE}/pairing`).flush(pairing);
    fixture.detectChanges();
  }

  it('zeigtKoppelHinweisWaehrendPressButton', () => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    initWith([], { state: 'PRESS_BUTTON', expiresAt, error: null });

    expect(el.textContent).toContain('Jetzt den Aktionsknopf an der Unterseite des Hubs drücken');
    expect(fixture.componentInstance.secondsLeft()).toBeGreaterThan(55);
    fixture.destroy();
  });

  it('sperrtEntkoppelnWaehrendPressButton', () => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    initWith([], { state: 'PRESS_BUTTON', expiresAt, error: null }, { ...HUB, paired: true });

    const unpairButton = Array.from(el.querySelectorAll('button'))
      .find(b => b.textContent?.includes('Entkoppeln')) as HTMLButtonElement;
    expect(unpairButton.disabled).toBeTrue();
    fixture.destroy();
  });

  it('pollingEndetBeiPaired', fakeAsync(() => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    initWith([], { state: 'PRESS_BUTTON', expiresAt, error: null });

    tick(2000);
    httpMock.expectOne(`${BASE}/pairing`).flush({ state: 'PAIRED', expiresAt: null, error: null });
    httpMock.expectOne(`${BASE}/hub`).flush({ ...HUB, paired: true });
    httpMock.expectOne(`${BASE}/sensors`).flush([SENSOR]);

    tick(2000);
    httpMock.expectNone(`${BASE}/pairing`);
    expect(fixture.componentInstance.pairing()?.state).toBe('PAIRED');
    fixture.destroy();
  }));

  it('zeigtFehlertextBeiFailed', fakeAsync(() => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    initWith([], { state: 'PRESS_BUTTON', expiresAt, error: null });

    tick(2000);
    httpMock.expectOne(`${BASE}/pairing`).flush({ state: 'FAILED', expiresAt: null, error: 'Zeit abgelaufen' });
    httpMock.expectOne(`${BASE}/hub`).flush(HUB);
    httpMock.expectOne(`${BASE}/sensors`).flush([]);
    fixture.detectChanges();

    expect(el.textContent).toContain('Zeit abgelaufen');
    fixture.destroy();
  }));

  it('loeschenNurFuerSensorenOhneHub', () => {
    initWith([SENSOR, GONE]);

    const rows = Array.from(el.querySelectorAll('.admin-dirigera__table tbody tr'));
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).not.toContain('Löschen');
    expect(rows[1].textContent).toContain('Löschen');
    expect(rows[1].textContent).toContain('Nicht mehr am Hub');
  });

  it('kontaktZeigtOffenZuStattTemperatur', () => {
    initWith([CONTACT, { ...CONTACT, id: 4, name: 'Kuechenfenster', open: false }, { ...CONTACT, id: 5, name: 'Neu', open: null }]);

    const rows = Array.from(el.querySelectorAll('.admin-dirigera__table tbody tr'));
    expect(rows[0].textContent).toContain('Offen');
    expect(rows[0].textContent).not.toContain('°C');
    expect(rows[1].textContent).toContain('Geschlossen');
    expect(rows[2].textContent).toContain('Zustand unbekannt');
  });

  it('thermometerZeigtWeiterTemperaturUndFeuchte', () => {
    initWith([SENSOR]);

    const row = el.querySelector('.admin-dirigera__table tbody tr')!;
    expect(row.textContent).toContain('21,5 °C');
    expect(row.textContent).toContain('48 %');
  });

  it('pollingEndetBeiDestroy', fakeAsync(() => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    initWith([], { state: 'PRESS_BUTTON', expiresAt, error: null });

    fixture.destroy();
    tick(4000);
    httpMock.expectNone(`${BASE}/pairing`);
    expect(fixture.componentInstance.pairing()?.state).toBe('PRESS_BUTTON');
  }));
});
