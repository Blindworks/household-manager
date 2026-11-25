import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { AdminHeatingComponent } from './admin-heating.component';
import { ThermostatService } from '../../services/thermostat.service';
import { Thermostat } from '../../models/thermostat.model';
import { TemperatureService } from '../../services/temperature.service';
import { CurrentTemperatureReading } from '../../models/temperature.model';

const WOHNZIMMER: Thermostat = {
  id: 1, name: 'Wohnzimmer', online: true, currentTemp: 20, targetTemp: 21,
  mode: 'MANUAL', heating: false, batteryLow: false, lastUpdated: null, roomSensorId: null
};
const KUECHE: Thermostat = { ...WOHNZIMMER, id: 2, name: 'Küche' };

describe('AdminHeatingComponent', () => {
  let fixture: ComponentFixture<AdminHeatingComponent>;
  let api: jasmine.SpyObj<ThermostatService>;
  let el: HTMLElement;
  let temperatures: jasmine.SpyObj<TemperatureService>;

  beforeEach(async () => {
    api = jasmine.createSpyObj<ThermostatService>('ThermostatService', ['list', 'setHub', 'rename', 'setRoomSensor']);
    api.list.and.returnValue(of([]));
    temperatures = jasmine.createSpyObj<TemperatureService>('TemperatureService', ['getCurrent']);
    temperatures.getCurrent.and.returnValue(of([
      { sensorId: 'weather:outdoor', name: 'Außen' },
      { sensorId: 'dirigera:3', name: 'Temperatur Wohnzimmer' }
    ] as CurrentTemperatureReading[]));
    await TestBed.configureTestingModule({
      imports: [AdminHeatingComponent],
      providers: [
        { provide: ThermostatService, useValue: api },
        { provide: TemperatureService, useValue: temperatures }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminHeatingComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  function searchButton(): HTMLButtonElement {
    return el.querySelector('[data-test="search"]') as HTMLButtonElement;
  }

  async function enterIp(ip: string): Promise<void> {
    fixture.componentInstance.ip = ip;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('sperrt den Knopf bei leerer IP', async () => {
    await enterIp('   ');
    expect(searchButton().disabled).toBeTrue();
    await enterIp('192.168.1.60');
    expect(searchButton().disabled).toBeFalse();
  });

  it('zeigt die Backend-Meldung bei 400', async () => {
    api.setHub.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 400, error: { message: 'Ungültige IP-Adresse.' } })));
    await enterIp('abc');
    searchButton().click();
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Ungültige IP-Adresse.');
  });

  it('zeigt die Backend-Meldung bei 502', async () => {
    api.setHub.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 502, error: { message: 'Hub nicht erreichbar.' } })));
    await enterIp('192.168.1.60');
    searchButton().click();
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Hub nicht erreichbar.');
  });

  it('listet nach erfolgreicher Suche die Thermostate', async () => {
    api.setHub.and.returnValue(of([WOHNZIMMER, KUECHE]));
    api.list.and.returnValue(of([WOHNZIMMER, KUECHE]));
    await enterIp('192.168.1.60');
    searchButton().click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.setHub).toHaveBeenCalledOnceWith('192.168.1.60');
    expect(el.querySelectorAll('[data-test="thermostat"]').length).toBe(2);
  });

  it('ruft beim Umbenennen rename genau einmal auf', async () => {
    api.list.and.returnValue(of([WOHNZIMMER]));
    fixture.componentInstance.ngOnInit();
    api.rename.and.returnValue(of({ ...WOHNZIMMER, name: 'Wohnen' }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    fixture.componentInstance.names[1] = 'Wohnen';
    fixture.detectChanges();
    (el.querySelector('[data-test="rename"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.rename).toHaveBeenCalledOnceWith(1, 'Wohnen');
  });

  it('laedt nach der Hub-Suche die komplette Liste neu (andere Hubs bleiben sichtbar)', async () => {
    api.setHub.and.returnValue(of([KUECHE]));
    api.list.and.returnValue(of([WOHNZIMMER, KUECHE]));
    await enterIp('192.168.1.61');
    api.list.calls.reset();
    searchButton().click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(1);
    expect(el.querySelectorAll('[data-test="thermostat"]').length).toBe(2);
  });

  describe('Raumfuehler', () => {
    function show(list: Thermostat[]): HTMLSelectElement {
      fixture.componentInstance['show'](list);
      fixture.detectChanges();
      return el.querySelector('[data-test="room-sensor"]') as HTMLSelectElement;
    }

    it('bietet die Fuehler ohne Wettervorhersage an', () => {
      const select = show([WOHNZIMMER]);
      const values = Array.from(select.options).map(o => o.value);
      expect(values).toEqual(['', 'dirigera:3']);
    });

    it('speichert die Auswahl und uebernimmt die Antwort', () => {
      api.setRoomSensor.and.returnValue(of({ ...WOHNZIMMER, roomSensorId: 'dirigera:3' }));
      const select = show([WOHNZIMMER]);
      select.value = 'dirigera:3';
      select.dispatchEvent(new Event('change'));
      expect(api.setRoomSensor).toHaveBeenCalledOnceWith(1, 'dirigera:3');
      expect(fixture.componentInstance.thermostats()[0].roomSensorId).toBe('dirigera:3');
    });

    it('setzt die Auswahl bei einem Fehler zurueck', () => {
      api.setRoomSensor.and.returnValue(throwError(() => new HttpErrorResponse({
        status: 400, error: { message: 'Unbekannter Temperaturfuehler: dirigera:3' }
      })));
      const select = show([WOHNZIMMER]);
      select.value = 'dirigera:3';
      select.dispatchEvent(new Event('change'));
      expect(select.value).toBe('');
      expect(fixture.componentInstance.errorMessage()).toContain('Unbekannter');
    });

    it('laesst einen verschwundenen, zugeordneten Fuehler waehlbar', () => {
      const select = show([{ ...WOHNZIMMER, roomSensorId: 'ble:9' }]);
      fixture.detectChanges();
      expect(select.value).toBe('ble:9');
      expect(select.selectedOptions[0].textContent).toContain('nicht gefunden');
    });
  });
});
