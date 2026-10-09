import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ThermostatService } from './thermostat.service';
import { Thermostat } from '../models/thermostat.model';

describe('ThermostatService (Mock-Modus)', () => {
  let service: ThermostatService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(ThermostatService);
  });

  const byId = (list: Thermostat[], id: number): Thermostat =>
    list.find(t => t.id === id) as Thermostat;

  it('liefert fuenf Raeume', async () => {
    const list = await firstValueFrom(service.list());
    expect(list.length).toBe(5);
  });

  it('setTarget wird danach in list() sichtbar', async () => {
    await firstValueFrom(service.setTarget(1, 22));
    const list = await firstValueFrom(service.list());
    expect(byId(list, 1).targetTemp).toBe(22);
  });

  it('setMode(2, OFF) setzt den Modus', async () => {
    const updated = await firstValueFrom(service.setMode(2, 'OFF'));
    expect(updated.mode).toBe('OFF');
    const list = await firstValueFrom(service.list());
    expect(byId(list, 2).mode).toBe('OFF');
  });

  it('bulk(null, 17) setzt alle online-Thermostate, offline bleibt unberuehrt', async () => {
    const before = await firstValueFrom(service.list());
    const offline = before.filter(t => !t.online);
    expect(offline.length).toBeGreaterThan(0);

    await firstValueFrom(service.bulk(null, 17));
    const after = await firstValueFrom(service.list());

    after.filter(t => t.online).forEach(t => expect(t.targetTemp).toBe(17));
    offline.forEach(o => expect(byId(after, o.id).targetTemp).toBe(o.targetTemp));
  });

  it('bulk mit Id-Liste trifft nur diese Thermostate', async () => {
    const before = await firstValueFrom(service.list());
    await firstValueFrom(service.bulk([1], 19));
    const after = await firstValueFrom(service.list());
    expect(byId(after, 1).targetTemp).toBe(19);
    const other = before.find(t => t.online && t.id !== 1) as Thermostat;
    expect(byId(after, other.id).targetTemp).toBe(other.targetTemp);
  });

  it('history liefert mindestens 24 Punkte', async () => {
    const history = await firstValueFrom(service.history(1, 'DAY'));
    expect(history.points.length).toBeGreaterThanOrEqual(24);
  });
});

describe('ThermostatService (HTTP-Modus)', () => {
  let service: ThermostatService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(ThermostatService);
    http = TestBed.inject(HttpTestingController);
    // Der Vertrag gilt fuer die echte API; der Mock wird hier gezielt abgeschaltet.
    (service as unknown as { useMock: boolean }).useMock = false;
  });

  afterEach(() => http.verify());

  it('haelt den API-Vertrag exakt ein', () => {
    service.list().subscribe();
    expect(http.expectOne('/api/v1/thermostats').request.method).toBe('GET');

    service.setTarget(3, 21.5).subscribe();
    const target = http.expectOne('/api/v1/thermostats/3/target');
    expect(target.request.method).toBe('PUT');
    expect(target.request.body).toEqual({ targetTemp: 21.5 });

    service.setMode(3, 'FROST_PROTECTION').subscribe();
    const mode = http.expectOne('/api/v1/thermostats/3/mode');
    expect(mode.request.method).toBe('PUT');
    expect(mode.request.body).toEqual({ mode: 'FROST_PROTECTION' });

    service.bulk([1, 2], 17).subscribe();
    const bulk = http.expectOne('/api/v1/thermostats/bulk');
    expect(bulk.request.method).toBe('POST');
    expect(bulk.request.body).toEqual({ ids: [1, 2], targetTemp: 17 });

    service.bulk(null, 17).subscribe();
    const bulkAll = http.expectOne('/api/v1/thermostats/bulk');
    expect(bulkAll.request.body).toEqual({ ids: null, targetTemp: 17 });

    service.history(3, 'WEEK').subscribe();
    const history = http.expectOne(r => r.url === '/api/v1/thermostats/3/history');
    expect(history.request.params.get('range')).toBe('WEEK');
  });
});
