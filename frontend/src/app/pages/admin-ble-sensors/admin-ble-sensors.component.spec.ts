import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { AdminBleSensorsComponent } from './admin-ble-sensors.component';
import { BleSensorAdminResponse } from '../../models/ble-sensor.model';

registerLocaleData(localeDe);

const URL = '/api/v1/ble-sensors';

const UNADOPTED: BleSensorAdminResponse = {
  id: 1, mac: '11:22:33:44:55:66', name: null, active: false, tabletId: 'wandtablet',
  lastSeenAt: '2026-10-13T09:00:00', lastTemperature: 19.3, lastHumidity: 61, batteryPercent: 80, lastRssi: -70
};
const KUECHE: BleSensorAdminResponse = {
  id: 2, mac: 'aa:bb:cc:dd:ee:ff', name: 'Küche', active: true, tabletId: 'wandtablet',
  lastSeenAt: '2026-10-13T09:00:30', lastTemperature: 21.5, lastHumidity: 50, batteryPercent: 100, lastRssi: -60
};
const ALT: BleSensorAdminResponse = { ...KUECHE, id: 3, mac: '01:02:03:04:05:06', name: 'Alt', active: false };

describe('AdminBleSensorsComponent', () => {
  let fixture: ComponentFixture<AdminBleSensorsComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminBleSensorsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminBleSensorsComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  async function loadWith(sensors: BleSensorAdminResponse[]): Promise<void> {
    fixture.detectChanges();
    httpMock.expectOne(URL).flush(sensors);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-ble-sensors__table tbody tr'));
  }

  function button(row: HTMLElement, label: string): HTMLButtonElement {
    const btn = Array.from(row.querySelectorAll('button')).find(b => b.textContent?.trim() === label);
    if (!btn) { throw new Error(`Kein Knopf „${label}“ in Zeile`); }
    return btn as HTMLButtonElement;
  }

  it('zeigt alle Sensoren mit Status-Badge, MAC als Fallback-Name', async () => {
    await loadWith([UNADOPTED, KUECHE, ALT]);
    const r = rows();
    expect(r.length).toBe(3);
    expect(r[0].textContent).toContain('11:22:33:44:55:66');
    expect(r[0].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Nicht übernommen');
    expect(r[1].textContent).toContain('Küche');
    expect(r[1].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Aktiv');
    expect(r[2].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Inaktiv');
  });

  it('zeigt Letztwerte und Batterie', async () => {
    await loadWith([KUECHE]);
    expect(rows()[0].textContent).toContain('21,5 °C');
    expect(rows()[0].textContent).toContain('50 %');
    expect(rows()[0].textContent).toContain('100 %');
  });

  it('Uebernehmen fragt den Namen und sendet active=true per Voll-PUT', async () => {
    await loadWith([UNADOPTED]);
    button(rows()[0], 'Übernehmen').click();
    fixture.detectChanges();
    await fixture.whenStable();
    const input = el.querySelector('.admin-ble-sensors__name-input') as HTMLInputElement;
    input.value = 'Bad';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button(rows()[0], 'Speichern').click();

    const req = httpMock.expectOne(`${URL}/1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'Bad', active: true });
    req.flush({ ...UNADOPTED, name: 'Bad', active: true });
    fixture.detectChanges();
    expect(rows()[0].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Aktiv');
  });

  it('Speichern ist ohne Namen gesperrt', async () => {
    await loadWith([UNADOPTED]);
    button(rows()[0], 'Übernehmen').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(button(rows()[0], 'Speichern').disabled).toBeTrue();
  });

  it('Deaktivieren sendet active=false und behaelt den Namen', async () => {
    await loadWith([KUECHE]);
    button(rows()[0], 'Deaktivieren').click();
    const req = httpMock.expectOne(`${URL}/2`);
    expect(req.request.body).toEqual({ name: 'Küche', active: false });
    req.flush({ ...KUECHE, active: false });
  });

  it('Loeschen gibt es nur fuer inaktive Sensoren und fragt nach', async () => {
    await loadWith([KUECHE, ALT]);
    expect(Array.from(rows()[0].querySelectorAll('button')).map(b => b.textContent?.trim())).not.toContain('Löschen');
    spyOn(window, 'confirm').and.returnValue(true);
    button(rows()[1], 'Löschen').click();
    const req = httpMock.expectOne(`${URL}/3`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    fixture.detectChanges();
    expect(rows().length).toBe(1);
  });

  it('ein fehlgeschlagener Refresh behaelt den letzten Stand', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne(URL).flush([KUECHE]);
    fixture.detectChanges();
    tick(30_000);
    httpMock.expectOne(URL).flush('kaputt', { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(rows().length).toBe(1);
    // Raeumt den Refresh-Timer ab; das End-Flush von fakeAsync loest ihn sonst ein weiteres Mal aus.
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('ein fehlgeschlagener Erstabruf blendet die Tabelle aus und meldet den Fehler', async () => {
    fixture.detectChanges();
    httpMock.expectOne(URL).flush('kaputt', { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('.admin-ble-sensors__table')).toBeNull();
    expect(el.textContent).toContain('konnten nicht geladen werden');
  });
});
