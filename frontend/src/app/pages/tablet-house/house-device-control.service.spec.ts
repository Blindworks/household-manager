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
import { NukiService } from '../../services/nuki.service';
import { BlinkService } from '../../services/blink.service';
import { TractiveService } from '../../services/tractive.service';
import { PetSupplyService } from '../../services/pet-supply.service';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { NukiLock } from '../../models/nuki.model';
import { BlinkCamera } from '../../models/blink.model';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
import { HOUSE_DEVICES, HouseDevice } from '../../shared/house/house-devices';
import { thermostatIdOf } from '../../shared/house/device-detail.util';
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
  let nuki: jasmine.SpyObj<NukiService>;
  let blink: jasmine.SpyObj<BlinkService>;
  let tractive: jasmine.SpyObj<TractiveService>;
  let supplies: jasmine.SpyObj<PetSupplyService>;
  let waste: jasmine.SpyObj<WasteCollectionService>;

  beforeEach(() => {
    temperatures = jasmine.createSpyObj<TemperatureService>('TemperatureService',
      ['getCurrent', 'getSeries', 'getSensorSeries']);
    thermostats = jasmine.createSpyObj<ThermostatService>('ThermostatService', ['list', 'history', 'setTarget', 'setMode']);
    power = jasmine.createSpyObj<PowerConsumerService>('PowerConsumerService', ['getHistory']);
    switches = jasmine.createSpyObj<SwitchService>('SwitchService', ['getSwitches', 'toggle']);
    devices = jasmine.createSpyObj<SmartDeviceService>('SmartDeviceService', ['getAllDevices', 'setLightState']);

    nuki = jasmine.createSpyObj<NukiService>('NukiService', ['getLocks', 'sendAction']);
    blink = jasmine.createSpyObj<BlinkService>('BlinkService',
      ['getCameras', 'setCameraArmed', 'takeSnapshot', 'thumbnailUrl']);
    tractive = jasmine.createSpyObj<TractiveService>('TractiveService', ['getPets', 'getWalks']);
    supplies = jasmine.createSpyObj<PetSupplyService>('PetSupplyService', ['getSupplies']);
    waste = jasmine.createSpyObj<WasteCollectionService>('WasteCollectionService', ['getUpcoming']);

    TestBed.configureTestingModule({
      providers: [
        HouseDeviceControlService,
        { provide: TemperatureService, useValue: temperatures },
        { provide: ThermostatService, useValue: thermostats },
        { provide: PowerConsumerService, useValue: power },
        { provide: SwitchService, useValue: switches },
        { provide: SmartDeviceService, useValue: devices },
        { provide: NukiService, useValue: nuki },
        { provide: BlinkService, useValue: blink },
        { provide: TractiveService, useValue: tractive },
        { provide: PetSupplyService, useValue: supplies },
        { provide: WasteCollectionService, useValue: waste }
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

    describe('Lauf-Helfer einer Maschine', () => {
      const RUNNING = 'input_boolean.manual_waschmaschine_laeuft';

      it('Aus ohne Rückfrage, Helfer laut frischem Abruf an ⇒ needs-confirm, kein toggle', async () => {
        switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'on'), switchEntity(RUNNING, 'on')]));

        const outcome = await firstValueFrom(
          service.setSwitch('switch.a', false, { runningEntityId: RUNNING, confirmed: false }));

        expect(outcome).toBe('needs-confirm');
        expect(switches.toggle).not.toHaveBeenCalled();
      });

      it('Aus nach Rückfrage, Helfer an ⇒ toggle', async () => {
        switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'on'), switchEntity(RUNNING, 'on')]));
        switches.toggle.and.returnValue(of(switchEntity('switch.a', 'off')));

        const outcome = await firstValueFrom(
          service.setSwitch('switch.a', false, { runningEntityId: RUNNING, confirmed: true }));

        expect(outcome).toBe('switched');
        expect(switches.toggle).toHaveBeenCalledOnceWith('switch.a');
      });

      it('Aus ohne Rückfrage, Helfer aus oder fehlend ⇒ toggle', async () => {
        switches.toggle.and.returnValue(of(switchEntity('switch.a', 'off')));
        switches.getSwitches.and.returnValues(
          of([switchEntity('switch.a', 'on'), switchEntity(RUNNING, 'off')]),
          of([switchEntity('switch.a', 'on')]));

        expect(await firstValueFrom(
          service.setSwitch('switch.a', false, { runningEntityId: RUNNING, confirmed: false }))).toBe('switched');
        expect(await firstValueFrom(
          service.setSwitch('switch.a', false, { runningEntityId: RUNNING, confirmed: false }))).toBe('switched');
        expect(switches.toggle).toHaveBeenCalledTimes(2);
      });

      it('schon aus ⇒ already, auch wenn der Helfer an ist', async () => {
        switches.getSwitches.and.returnValue(of([switchEntity('switch.a', 'off'), switchEntity(RUNNING, 'on')]));

        expect(await firstValueFrom(
          service.setSwitch('switch.a', false, { runningEntityId: RUNNING, confirmed: false }))).toBe('already');
        expect(switches.toggle).not.toHaveBeenCalled();
      });
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
    beforeEach(() => switches.getSwitches.and.returnValue(of([switchEntity('switch.flur', 'on')])));

    it('setBrightness liest den Schaltzustand frisch', async () => {
      devices.getAllDevices.and.returnValue(of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 40)]));
      devices.setLightState.and.returnValue(of(device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 80)));

      expect(await firstValueFrom(service.setBrightness('switch.flur', 80))).toBe('set');
      expect(switches.getSwitches).toHaveBeenCalledWith(undefined, 'all');
    });

    for (const state of ['off', 'unavailable']) {
      it(`setBrightness bei frischem Zustand ${state} ⇒ lamp-off, kein setLightState`, async () => {
        switches.getSwitches.and.returnValue(of([switchEntity('switch.flur', state)]));
        devices.getAllDevices.and.returnValue(of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 40)]));

        expect(await firstValueFrom(service.setBrightness('switch.flur', 80))).toBe('lamp-off');
        expect(devices.setLightState).not.toHaveBeenCalled();
      });
    }

    it('setBrightness: Schalter fehlt in der Liste ⇒ lamp-off', async () => {
      switches.getSwitches.and.returnValue(of([]));

      expect(await firstValueFrom(service.setBrightness('switch.flur', 80))).toBe('lamp-off');
      expect(devices.setLightState).not.toHaveBeenCalled();
    });

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

      expect(await firstValueFrom(service.lightDevice('switch.flur'))).toEqual(jasmine.objectContaining({ dimmable: true, brightness: 40 }));
    });

    it('lightDevice: ohne BRIGHTNESS ⇒ dimmable false; unbekannt ⇒ null', async () => {
      devices.getAllDevices.and.returnValue(of([device(1, 'switch.a', ['SWITCH'])]));

      expect(await firstValueFrom(service.lightDevice('switch.a'))).toEqual(jasmine.objectContaining({ dimmable: false, brightness: null }));
      expect(await firstValueFrom(service.lightDevice('switch.x'))).toBeNull();
      expect(devices.getAllDevices).toHaveBeenCalledTimes(1);
    });

    it('lampBrightness: Helligkeit je Entity nur dimmbarer Lampen, immer frisch geladen', async () => {
      devices.getAllDevices.and.returnValues(
        of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 40), device(1, 'switch.a', ['SWITCH'], 70),
          device(2, 'switch.b', ['SWITCH', 'BRIGHTNESS'])]),
        of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 90)]));

      expect(await firstValueFrom(service.lampBrightness())).toEqual(new Map([['switch.flur', 40]]));
      expect(await firstValueFrom(service.lampBrightness())).toEqual(new Map([['switch.flur', 90]]));
      // Der frische Stand dient auch dem Detail-Feld.
      expect(await firstValueFrom(service.lightDevice('switch.flur'))).toEqual(jasmine.objectContaining({ dimmable: true, brightness: 90 }));
      expect(devices.getAllDevices).toHaveBeenCalledTimes(2);
    });

    it('nach setBrightness wird die Geräteliste neu geladen', async () => {
      devices.getAllDevices.and.returnValues(
        of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 40)]),
        of([device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 80)]));
      devices.setLightState.and.returnValue(of(device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS'], 80)));

      await firstValueFrom(service.setBrightness('switch.flur', 80));
      const light = await firstValueFrom(service.lightDevice('switch.flur'));

      expect(devices.getAllDevices).toHaveBeenCalledTimes(2);
      expect(light).toEqual(jasmine.objectContaining({ dimmable: true, brightness: 80 }));
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

  describe('Farbe', () => {
    it('setLightColor bei ausgeschalteter Lampe ⇒ lamp-off, kein setLightState', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.flur', 'off')]));
      devices.getAllDevices.and.returnValue(of([device(7, 'switch.flur', ['SWITCH', 'COLOR'])]));

      expect(await firstValueFrom(service.setLightColor('switch.flur', { hue: 225, saturation: 100 }))).toBe('lamp-off');
      expect(devices.setLightState).not.toHaveBeenCalled();
    });

    it('setLightColor bei on ⇒ setLightState(deviceId, Farbe)', async () => {
      switches.getSwitches.and.returnValue(of([switchEntity('switch.flur', 'on')]));
      devices.getAllDevices.and.returnValue(of([device(7, 'switch.flur', ['SWITCH', 'COLOR'])]));
      devices.setLightState.and.returnValue(of(device(7, 'switch.flur', ['SWITCH', 'COLOR'])));

      expect(await firstValueFrom(service.setLightColor('switch.flur', { hue: 225, saturation: 100 }))).toBe('set');
      expect(devices.setLightState).toHaveBeenCalledOnceWith(7, { hue: 225, saturation: 100 });
    });

    it('lightDevice mappt COLOR, COLOR_TEMP, Farbwerte und Metadata-Spanne', async () => {
      const lamp = {
        ...device(7, 'switch.flur', ['SWITCH', 'BRIGHTNESS', 'COLOR', 'COLOR_TEMP'], 40),
        hue: 120, saturation: 90, colorTemp: 0,
        metadata: { colorTempRangeMin: 2000, colorTempRangeMax: 9000 }
      } as unknown as SmartDevice;
      devices.getAllDevices.and.returnValue(of([lamp]));

      expect(await firstValueFrom(service.lightDevice('switch.flur'))).toEqual({
        dimmable: true, brightness: 40, supportsColor: true, supportsColorTemp: true,
        hue: 120, saturation: 90, colorTemp: 0, colorTempRange: { min: 2000, max: 9000 }
      });
    });
  });

  describe('Tuer, Kamera, Modus', () => {
    const lock = (state: string): NukiLock => ({
      smartlockId: 5, name: 'Haustür', state, doorState: null, batteryCharge: 80, batteryCritical: false
    });
    const camera = (armed: boolean): BlinkCamera => ({
      cameraId: '511671', name: 'Tür', type: 'mini', armed, battery: null, syncName: 's', syncArmed: true
    });

    it('lockDoor: bereits verriegelt ⇒ already, kein sendAction', async () => {
      nuki.getLocks.and.returnValue(of([lock('locked')]));
      expect(await firstValueFrom(service.lockDoor(5))).toBe('already');
      expect(nuki.sendAction).not.toHaveBeenCalled();
    });

    it('lockDoor: entriegelt ⇒ sendAction(id, LOCK)', async () => {
      nuki.getLocks.and.returnValue(of([lock('unlocked')]));
      nuki.sendAction.and.returnValue(of(undefined));
      expect(await firstValueFrom(service.lockDoor(5))).toBe('done');
      expect(nuki.sendAction).toHaveBeenCalledOnceWith(5, 'LOCK');
    });

    it('lockDoor: Schloss fehlt ⇒ unavailable', async () => {
      nuki.getLocks.and.returnValue(of([]));
      expect(await firstValueFrom(service.lockDoor(5))).toBe('unavailable');
      expect(nuki.sendAction).not.toHaveBeenCalled();
    });

    it('setCameraArmed: Zustand schon gleich ⇒ already, kein Schalten', async () => {
      blink.getCameras.and.returnValue(of([camera(false)]));
      expect(await firstValueFrom(service.setCameraArmed('511671', false))).toBe('already');
      expect(blink.setCameraArmed).not.toHaveBeenCalled();
    });

    it('setCameraArmed: abweichend ⇒ schaltet ⇒ done', async () => {
      blink.getCameras.and.returnValue(of([camera(true)]));
      blink.setCameraArmed.and.returnValue(of(undefined));
      expect(await firstValueFrom(service.setCameraArmed('511671', false))).toBe('done');
      expect(blink.setCameraArmed).toHaveBeenCalledOnceWith('511671', false);
    });

    it('setCameraArmed: Kamera fehlt ⇒ unavailable', async () => {
      blink.getCameras.and.returnValue(of([]));
      expect(await firstValueFrom(service.setCameraArmed('511671', false))).toBe('unavailable');
      expect(blink.setCameraArmed).not.toHaveBeenCalled();
    });

    it('setThermostatMode: Modus schon gesetzt ⇒ already', async () => {
      thermostats.list.and.returnValue(of([{ ...thermostat(8, null), mode: 'OFF' }]));
      expect(await firstValueFrom(service.setThermostatMode(8, 'OFF'))).toBe('already');
      expect(thermostats.setMode).not.toHaveBeenCalled();
    });

    it('setThermostatMode: abweichend ⇒ setMode ⇒ done; fehlend ⇒ unavailable', async () => {
      thermostats.list.and.returnValue(of([thermostat(8, null)]));
      thermostats.setMode.and.returnValue(of(thermostat(8, null)));
      expect(await firstValueFrom(service.setThermostatMode(8, 'OFF'))).toBe('done');
      expect(thermostats.setMode).toHaveBeenCalledOnceWith(8, 'OFF');
      expect(await firstValueFrom(service.setThermostatMode(99, 'OFF'))).toBe('unavailable');
    });

    it('takeSnapshot wirft den Blob weg; thumbnailUrl reicht durch', async () => {
      blink.takeSnapshot.and.returnValue(of(new Blob()));
      blink.thumbnailUrl.and.returnValue('/x?t=3');
      expect(await firstValueFrom(service.takeSnapshot('511671'))).toBeUndefined();
      expect(service.thumbnailUrl('511671', 3)).toBe('/x?t=3');
      expect(blink.thumbnailUrl).toHaveBeenCalledWith('511671', 3);
    });
  });

  describe('loadExtras', () => {
    const byKind = <K extends HouseDevice['kind']>(kind: K): Extract<HouseDevice, { kind: K }> =>
      HOUSE_DEVICES.find(d => d.kind === kind) as Extract<HouseDevice, { kind: K }>;
    const walk = (end: string): TractiveWalk => ({ start: end, end, durationMinutes: 30, distanceMeters: 1000 });
    const pet = (trackerId: string): TractivePet => ({ trackerId, name: 'Toni', zone: 'Zuhause', batteryPercent: 80 });

    it('Toni: Tier (tracker-id ohne Gross-/Kleinschreibung) und spaetester Spaziergang', async () => {
      const toni = byKind('pet');
      const tracker = toni.homeEntityId.replace('binary_sensor.tractive_', '').replace('_home', '').toUpperCase();
      tractive.getPets.and.returnValue(of([pet('OTHER'), pet(tracker)]));
      tractive.getWalks.and.returnValue(of([walk('2026-11-06T09:00:00'), walk('2026-11-05T10:00:00')]));
      supplies.getSupplies.and.returnValue(of([]));

      const extras = await firstValueFrom(service.loadExtras(toni));

      expect(extras.pet?.pet?.batteryPercent).toBe(80);
      expect(extras.pet?.lastWalk?.end).toBe('2026-11-06T09:00:00');
      expect(tractive.getWalks).toHaveBeenCalledWith(tracker, 7);
    });

    it('Toni: getPets scheitert ⇒ supplies vorhanden, kein Schluessel pet', async () => {
      tractive.getPets.and.returnValue(throwError(() => new Error('x')));
      supplies.getSupplies.and.returnValue(of([]));

      const extras = await firstValueFrom(service.loadExtras(byKind('pet')));

      expect(extras.supplies).toEqual([]);
      expect('pet' in extras).toBeFalse();
    });

    it('Toni: getWalks scheitert ⇒ Tier bleibt, kein lastWalk', async () => {
      const toni = byKind('pet');
      const tracker = toni.homeEntityId.replace('binary_sensor.tractive_', '').replace('_home', '');
      tractive.getPets.and.returnValue(of([pet(tracker)]));
      tractive.getWalks.and.returnValue(throwError(() => new Error('x')));
      supplies.getSupplies.and.returnValue(of([]));

      const extras = await firstValueFrom(service.loadExtras(toni));

      expect(extras.pet?.pet?.batteryPercent).toBe(80);
      expect(extras.pet?.lastWalk).toBeUndefined();
    });

    it('Schloss fehlt in der Liste ⇒ lock null', async () => {
      nuki.getLocks.and.returnValue(of([]));
      expect((await firstValueFrom(service.loadExtras(byKind('door')))).lock).toBeNull();
    });

    it('Kamera fehlt in der Liste ⇒ camera null', async () => {
      blink.getCameras.and.returnValue(of([]));
      expect((await firstValueFrom(service.loadExtras(byKind('camera')))).camera).toBeNull();
    });

    it('getUpcoming scheitert ⇒ kein Schluessel waste', async () => {
      waste.getUpcoming.and.returnValue(throwError(() => new Error('x')));
      expect('waste' in await firstValueFrom(service.loadExtras(byKind('waste')))).toBeFalse();
    });

    it('Thermostat: ein einziger thermostats.list, liefert Raumwert und Modus', async () => {
      const device = HOUSE_DEVICES.find(d => d.kind === 'thermostat' && thermostatIdOf(d.target) !== null) as
        Extract<HouseDevice, { kind: 'thermostat' }>;
      const id = thermostatIdOf(device.target)!;
      thermostats.list.and.returnValue(of([{ ...thermostat(id, 'dirigera:3'), mode: 'FROST_PROTECTION' }]));
      temperatures.getCurrent.and.returnValue(of([reading('dirigera:3', 'sensor.bad_temperature', 19.8)]));

      const extras = await firstValueFrom(service.loadExtras(device));

      expect(thermostats.list).toHaveBeenCalledTimes(1);
      expect(extras.thermostatMode).toBe('FROST_PROTECTION');
      expect(extras.roomValue).toEqual({ text: '19,8°', stale: false });
    });

    it('andere Arten ⇒ leer', async () => {
      expect(await firstValueFrom(service.loadExtras(byKind('contact')))).toEqual({});
    });
  });
});
