import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';
import { HouseDeviceControlService } from './house-device-control.service';
import { TemperatureService } from '../../services/temperature.service';
import { ThermostatService } from '../../services/thermostat.service';
import { PowerConsumerService } from '../../services/power-consumer.service';
import { SwitchService } from '../../services/switch.service';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SwitchEntity } from '../../models/switch.model';
import { SmartDevice } from '../../models/smart-device.model';
import { Thermostat } from '../../models/thermostat.model';
import { CurrentTemperatureReading, TemperatureSensorSeries } from '../../models/temperature.model';

function switchEntity(entityId: string, state: string): SwitchEntity {
  return {
    entityId, domain: 'SWITCH', source: 'KASA', displayName: entityId, state,
    available: state !== 'unavailable', icon: 'power', confirmRequired: false,
    toggleCount: 0, lastToggledAt: null
  };
}

function device(id: number, entityId: string, capabilities: string[], brightness?: number): SmartDevice {
  return {
    id, deviceType: 'TAPO', externalDeviceId: 'x' + id, deviceName: 'Lampe ' + id, model: 'L530',
    ipAddress: null, isOnline: true, isPoweredOn: true, capabilities, metadata: null,
    brightness, confirmRequired: false, entityId, createdAt: '', updatedAt: ''
  };
}

function thermostat(id: number, roomSensorId: string | null): Thermostat {
  return {
    id, name: 'Bad', online: true, currentTemp: 20, targetTemp: 21, mode: 'MANUAL', heating: false,
    batteryLow: false, lastUpdated: null, roomSensorId
  };
}

function reading(sensorId: string, entityId: string, temperature = 19.8, measuredAt = new Date().toISOString()):
  CurrentTemperatureReading {
  return { sensorId, entityId, name: 'Temperatur Bad', source: 'DIRIGERA', temperature, measuredAt };
}

function series(sensorId: string, name = 'Bad'): TemperatureSensorSeries {
  return {
    sensorId, name, source: 'ZIGBEE',
    temperature: [{ time: '2026-11-05T10:00:00', value: 20.1 }], humidity: []
  };
}

describe('HouseDeviceControlService', () => {
  let service: HouseDeviceControlService;
  let temperatures: jasmine.SpyObj<TemperatureService>;
  let thermostats: jasmine.SpyObj<ThermostatService>;
  let power: jasmine.SpyObj<PowerConsumerService>;
  let switches: jasmine.SpyObj<SwitchService>;
  let devices: jasmine.SpyObj<SmartDeviceService>;

  beforeEach(() => {
    temperatures = jasmine.createSpyObj<TemperatureService>('TemperatureService',
      ['getCurrent', 'getSeries', 'getSensorSeries']);
    thermostats = jasmine.createSpyObj<ThermostatService>('ThermostatService', ['list', 'history', 'setTarget']);
    power = jasmine.createSpyObj<PowerConsumerService>('PowerConsumerService', ['getHistory']);
    switches = jasmine.createSpyObj<SwitchService>('SwitchService', ['getSwitches', 'toggle']);
    devices = jasmine.createSpyObj<SmartDeviceService>('SmartDeviceService', ['getAllDevices', 'setLightState']);

    TestBed.configureTestingModule({
      providers: [
        HouseDeviceControlService,
        { provide: TemperatureService, useValue: temperatures },
        { provide: ThermostatService, useValue: thermostats },
        { provide: PowerConsumerService, useValue: power },
        { provide: SwitchService, useValue: switches },
        { provide: SmartDeviceService, useValue: devices }
      ]
    });
    service = TestBed.inject(HouseDeviceControlService);
  });

  describe('setSwitch', () => {
    it('setSwitch(on:true) bei frischem Zustand off ⇒ toggle; Ergebnis switched', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'off')]));
      switches.toggle.and.returnValue(of(switchEntity('switch.a', 'on')));

      const outcome = await firstValueFrom(service.setSwitch('switch.a', true));

      expect(switches.getSwitches).toHaveBeenCalledWith(undefined, 'all');
      expect(switches.toggle).toHaveBeenCalledOnceWith('switch.a');
      expect(outcome).toBe('switched');
    });

    it('setSwitch(on:false) bei frischem Zustand on ⇒ toggle', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'on')]));
      switches.toggle.and.returnValue(of(switchEntity('switch.a', 'off')));

      const outcome = await firstValueFrom(service.setSwitch('switch.a', false));

      expect(switches.toggle).toHaveBeenCalledOnceWith('switch.a');
      expect(outcome).toBe('switched');
    });

    it('setSwitch(on:true) bei frischem Zustand on ⇒ kein toggle, already (Hintergrund-Refresh-Fall)', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'on')]));

      const outcome = await firstValueFrom(service.setSwitch('switch.a', true));

      expect(switches.toggle).not.toHaveBeenCalled();
      expect(outcome).toBe('already');
    });

    it('setSwitch(on:false) bei frischem Zustand off ⇒ kein toggle, already', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'off')]));

      expect(await firstValueFrom(service.setSwitch('switch.a', false))).toBe('already');
      expect(switches.toggle).not.toHaveBeenCalled();
    });

    it('setSwitch bei unavailable ⇒ kein toggle, unavailable', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'unavailable')]));

      expect(await firstValueFrom(service.setSwitch('switch.a', false))).toBe('unavailable');
      expect(await firstValueFrom(service.setSwitch('switch.a', true))).toBe('unavailable');
      expect(switches.toggle).not.toHaveBeenCalled();
    });

    it('setSwitch bei fehlender Entität ⇒ kein toggle, unavailable', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.b', 'off')]));

      expect(await firstValueFrom(service.setSwitch('switch.a', true))).toBe('unavailable');
      expect(switches.toggle).not.toHaveBeenCalled();
    });

    it('liest bei jedem Aufruf frisch (kein Cache)', async () => {
      switches.getSwitches.and.returnValues(of([switchEntity('switch.a', 'off')]), of([switchEntity('switch.a', 'on')]));
      switches.toggle.and.returnValue(of(switchEntity('switch.a', 'on')));

      await firstValueFrom(service.setSwitch('switch.a', true));
      const second = await firstValueFrom(service.setSwitch('switch.a', true));

      expect(switches.getSwitches).toHaveBeenCalledTimes(2);
      expect(second).toBe('already');
      expect(switches.toggle).toHaveBeenCalledTimes(1);
    });

    it('scheitert der frische Abruf ⇒ Fehler, kein toggle', async () => {
      switches.getSwitches.and.returnValue(throwError(() => new Error('weg')));

      await expectAsync(firstValueFrom(service.setSwitch('switch.a', true))).toBeRejected();
      expect(switches.toggle).not.toHaveBeenCalled();
    });
  });

  describe('setTarget', () => {
    beforeEach(() => thermostats.setTarget.and.returnValue(of(thermostat(3, null))));

    it('setTarget(31) ⇒ setTarget(id, 30); setTarget(20.3) ⇒ 20.5; setTarget(4) ⇒ 5', async () => {
      await firstValueFrom(service.setTarget(3, 31));
      await firstValueFrom(service.setTarget(3, 20.3));
      await firstValueFrom(service.setTarget(3, 4));

      expect(thermostats.setTarget.calls.allArgs()).toEqual([[3, 30], [3, 20.5], [3, 5]]);
    });
  });

  describe('loadHistory', () => {
    it('temperature: entityId aus /current ⇒ getSensorSeries(sensorId, range)', async () => {
      temperatures.getCurrent.and.returnValue(of([reading('dirigera:3', 'sensor.bad_temperature')]));
      temperatures.getSensorSeries.and.returnValue(of(series('dirigera:3')));

      const result = await firstValueFrom(service.loadHistory({ type: 'temperature', entityId: 'sensor.bad_temperature' }, 'WEEK'));

      expect(temperatures.getSensorSeries).toHaveBeenCalledOnceWith('dirigera:3', 'WEEK');
      expect(temperatures.getSeries).not.toHaveBeenCalled();
      expect(result).toEqual([{ name: 'Bad', points: [{ at: '2026-11-05T10:00:00', value: 20.1 }] }]);
    });

    it('temperature: nicht in /current, aber in getSeries ⇒ dort gefunden', async () => {
      temperatures.getCurrent.and.returnValue(of([reading('dirigera:3', 'sensor.anderer_temperature')]));
      temperatures.getSeries.and.returnValue(of([
        { ...series('zigbee:wohnzimmer'), entityId: 'sensor.zigbee_wohnzimmer_temperature' }
      ]));
      temperatures.getSensorSeries.and.returnValue(of(series('zigbee:wohnzimmer')));

      const result = await firstValueFrom(
        service.loadHistory({ type: 'temperature', entityId: 'sensor.zigbee_wohnzimmer_temperature' }, 'DAY'));

      expect(temperatures.getSeries).toHaveBeenCalledOnceWith('DAY');
      expect(temperatures.getSensorSeries).toHaveBeenCalledOnceWith('zigbee:wohnzimmer', 'DAY');
      expect(result).not.toBeNull();
    });

    it('temperature: nirgends ⇒ null', async () => {
      temperatures.getCurrent.and.returnValue(of([]));
      temperatures.getSeries.and.returnValue(of([]));

      const result = await firstValueFrom(service.loadHistory({ type: 'temperature', entityId: 'sensor.x' }, 'DAY'));

      expect(result).toBeNull();
      expect(temperatures.getSensorSeries).not.toHaveBeenCalled();
    });

    it('temperature: kein Treffer wird nicht gemerkt — ein zurückgekehrter Fühler bekommt seinen Verlauf', async () => {
      const source = { type: 'temperature', entityId: 'sensor.bad_temperature' } as const;
      temperatures.getCurrent.and.returnValues(of([]), of([reading('dirigera:3', 'sensor.bad_temperature')]));
      temperatures.getSeries.and.returnValue(of([]));
      temperatures.getSensorSeries.and.returnValue(of(series('dirigera:3')));

      expect(await firstValueFrom(service.loadHistory(source, 'DAY'))).toBeNull();
      const second = await firstValueFrom(service.loadHistory(source, 'DAY'));

      expect(temperatures.getCurrent).toHaveBeenCalledTimes(2);
      expect(temperatures.getSensorSeries).toHaveBeenCalledOnceWith('dirigera:3', 'DAY');
      expect(second).not.toBeNull();
    });

    it('temperature: sensorId wird je Instanz gecacht', async () => {
      temperatures.getCurrent.and.returnValue(of([reading('dirigera:3', 'sensor.bad_temperature')]));
      temperatures.getSensorSeries.and.returnValue(of(series('dirigera:3')));
      const source = { type: 'temperature', entityId: 'sensor.bad_temperature' } as const;

      await firstValueFrom(service.loadHistory(source, 'DAY'));
      await firstValueFrom(service.loadHistory(source, 'WEEK'));

      expect(temperatures.getCurrent).toHaveBeenCalledTimes(1);
      expect(temperatures.getSensorSeries).toHaveBeenCalledTimes(2);
    });

    it('thermostat ⇒ zwei Serien, Soll dashed', async () => {
      thermostats.history.and.returnValue(of({
        points: [
          { time: 't1', currentTemp: 19.5, targetTemp: 21, heating: true },
          { time: 't2', currentTemp: null, targetTemp: 21, heating: false }
        ]
      }));

      const result = await firstValueFrom(service.loadHistory({ type: 'thermostat', thermostatId: 8 }, 'DAY'));

      expect(thermostats.history).toHaveBeenCalledOnceWith(8, 'DAY');
      expect(result).toEqual([
        { name: 'Ist', points: [{ at: 't1', value: 19.5 }, { at: 't2', value: null }] },
        { name: 'Soll', points: [{ at: 't1', value: 21 }, { at: 't2', value: 21 }], dashed: true }
      ]);
    });

    it('power ⇒ eine Serie aus dem Leistungsverlauf', async () => {
      power.getHistory.and.returnValue(of({
        entityId: 'sensor.p', displayName: 'Waschmaschine', points: [{ time: 't1', value: null }, { time: 't2', value: 300 }]
      }));

      const result = await firstValueFrom(service.loadHistory({ type: 'power', entityId: 'sensor.p' }, 'WEEK'));

      expect(power.getHistory).toHaveBeenCalledOnceWith('sensor.p', 'WEEK');
      expect(result).toEqual([{ name: 'Waschmaschine', points: [{ at: 't1', value: null }, { at: 't2', value: 300 }] }]);
    });
  });

  describe('Licht', () => {
    it('setBrightness ⇒ setLightState(device.id, { brightness }) über entityId-Treffer', async () => {
      devices.getAllDevices.and.returnValue(of([device(1, 'switch.a', ['SWITCH']), device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 40)]));
      devices.setLightState.and.returnValue(of(device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 80)));

      await firstValueFrom(service.setBrightness('switch.flur', 80));

      expect(devices.setLightState).toHaveBeenCalledOnceWith(7, { brightness: 80 });
    });

    it('setBrightness ohne Treffer ⇒ Fehler-Observable', async () => {
      devices.getAllDevices.and.returnValue(of([device(1, 'switch.a', ['SWITCH'])]));

      await expectAsync(firstValueFrom(service.setBrightness('switch.flur', 80))).toBeRejected();
      expect(devices.setLightState).not.toHaveBeenCalled();
    });

    it('lightDevice: BRIGHTNESS in capabilities ⇒ dimmable true mit brightness', async () => {
      devices.getAllDevices.and.returnValue(of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 40)]));

      expect(await firstValueFrom(service.lightDevice('switch.flur'))).toEqual({ dimmable: true, brightness: 40 });
    });

    it('lightDevice: ohne BRIGHTNESS ⇒ dimmable false; unbekannt ⇒ null', async () => {
      devices.getAllDevices.and.returnValue(of([device(1, 'switch.a', ['SWITCH'])]));

      expect(await firstValueFrom(service.lightDevice('switch.a'))).toEqual({ dimmable: false, brightness: null });
      expect(await firstValueFrom(service.lightDevice('switch.x'))).toBeNull();
      expect(devices.getAllDevices).toHaveBeenCalledTimes(1);
    });

    it('nach setBrightness wird die Geräteliste neu geladen', async () => {
      devices.getAllDevices.and.returnValues(
        of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 40)]),
        of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 80)]));
      devices.setLightState.and.returnValue(of(device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 80)));

      await firstValueFrom(service.setBrightness('switch.flur', 80));
      const light = await firstValueFrom(service.lightDevice('switch.flur'));

      expect(devices.getAllDevices).toHaveBeenCalledTimes(2);
      expect(light).toEqual({ dimmable: true, brightness: 80 });
    });
  });

  describe('thermostatRoom', () => {
    it('Raumfühler zugeordnet ⇒ Wert mit Grad, frisch', async () => {
      thermostats.list.and.returnValue(of([thermostat(8, 'dirigera:3')]));
      temperatures.getCurrent.and.returnValue(of([reading('dirigera:3', 'sensor.bad_temperature', 19.8)]));

      expect(await firstValueFrom(service.thermostatRoom(8))).toEqual({ text: '19,8°', stale: false });
    });

    it('ohne Raumfühler ⇒ null', async () => {
      thermostats.list.and.returnValue(of([thermostat(8, null)]));
      temperatures.getCurrent.and.returnValue(of([]));

      expect(await firstValueFrom(service.thermostatRoom(8))).toBeNull();
    });

    it('Fühler zugeordnet, aber nicht gelistet ⇒ MISSING statt null', async () => {
      thermostats.list.and.returnValue(of([thermostat(8, 'dirigera:3')]));
      temperatures.getCurrent.and.returnValue(of([]));

      expect(await firstValueFrom(service.thermostatRoom(8))).toEqual({ text: '–', stale: true });
    });

    it('veralteter Fühler ⇒ stale', async () => {
      thermostats.list.and.returnValue(of([thermostat(8, 'dirigera:3')]));
      temperatures.getCurrent.and.returnValue(of([
        reading('dirigera:3', 'sensor.bad_temperature', 19.8, new Date(Date.now() - 3 * 3600_000).toISOString())
      ]));

      expect(await firstValueFrom(service.thermostatRoom(8))).toEqual({ text: '19,8°', stale: true });
    });

    it('unbekanntes Thermostat ⇒ null', async () => {
      thermostats.list.and.returnValue(of([thermostat(8, 'dirigera:3')]));
      temperatures.getCurrent.and.returnValue(of([]));

      expect(await firstValueFrom(service.thermostatRoom(99))).toBeNull();
    });
  });
});
