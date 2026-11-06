import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of, throwError } from 'rxjs';
import { catchError, map, switchMap, tap } from 'rxjs/operators';
import { TemperatureService } from '../../services/temperature.service';
import { ThermostatService } from '../../services/thermostat.service';
import { PowerConsumerService } from '../../services/power-consumer.service';
import { SwitchService } from '../../services/switch.service';
import { SmartDeviceService } from '../../services/smart-device.service';
import { NukiService } from '../../services/nuki.service';
import { BlinkService } from '../../services/blink.service';
import { TractiveService } from '../../services/tractive.service';
import { PetSupplyService } from '../../services/pet-supply.service';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { LightStateRequest, SmartDevice } from '../../models/smart-device.model';
import { Thermostat, ThermostatMode } from '../../models/thermostat.model';
import { CurrentTemperatureReading } from '../../models/temperature.model';
import { HouseDevice } from '../../shared/house/house-devices';
import { DetailExtras, LightExtras, LockExtras, PetExtras, cameraIdOf, smartlockIdOf, trackerIdOf } from '../../shared/house/detail-extras.util';
import { LightColorRequest, colorTempRange } from '../../shared/house/light-color.util';
import { thermostatIdOf } from '../../shared/house/device-detail.util';
import { HistorySource } from '../../shared/house/device-detail.util';
import { BubbleValue, MISSING } from '../../shared/house/house-bubbles.util';
import { clampTarget, roomReading } from '../../shared/thermostat-view.util';

export interface HistoryPoint { at: string; value: number | null }
export interface HistorySeries { name: string; points: HistoryPoint[]; dashed?: boolean }
export type SwitchOutcome = 'switched' | 'already' | 'unavailable' | 'needs-confirm';
/** Lauf-Helfer einer Maschine; `confirmed` = der Nutzer hat das Ausschalten im Dialog bestaetigt. */
export interface SwitchGuard { runningEntityId: string; confirmed: boolean }
export type HistoryRange = 'DAY' | 'WEEK';
/** 'lamp-off' = Lampe ist laut frischem Zustand nicht `on`; es wurde nichts geschrieben. */
export type BrightnessOutcome = 'set' | 'lamp-off';
/** Ergebnis einer Schalt-Aktion nach frischem Lesen. */
export type ActionOutcome = 'done' | 'already' | 'unavailable';
/** Vorlaeufiger Alias bis die Seite auf `LightExtras` umgestellt ist (Task 5). */
export type LightInfo = LightExtras;

/**
 * Lesen und Schalten fuer das Geraete-Detail der Hausansicht. Wird von der
 * Seite bereitgestellt (nicht root): die Caches (sensorId je Entity-ID,
 * Smart-Device-Liste) leben genau so lange wie die Seite.
 */
@Injectable()
export class HouseDeviceControlService {
  private readonly temperatures = inject(TemperatureService);
  private readonly thermostats = inject(ThermostatService);
  private readonly power = inject(PowerConsumerService);
  private readonly switches = inject(SwitchService);
  private readonly smartDevices = inject(SmartDeviceService);
  private readonly nuki = inject(NukiService);
  private readonly blink = inject(BlinkService);
  private readonly tractive = inject(TractiveService);
  private readonly petSupplies = inject(PetSupplyService);
  private readonly waste = inject(WasteCollectionService);

  private readonly sensorIds = new Map<string, string>();
  private deviceList: SmartDevice[] | null = null;

  /** null = kein Verlauf verfuegbar. */
  loadHistory(source: HistorySource, range: HistoryRange): Observable<HistorySeries[] | null> {
    switch (source.type) {
      case 'temperature':
        return this.temperatureHistory(source.entityId, range);
      case 'thermostat':
        return this.thermostats.history(source.thermostatId, range).pipe(map(response => [
          { name: 'Ist', points: response.points.map(p => ({ at: p.time, value: p.currentTemp })) },
          { name: 'Soll', points: response.points.map(p => ({ at: p.time, value: p.targetTemp })), dashed: true }
        ]));
      case 'power':
        return this.power.getHistory(source.entityId, range).pipe(map(history => [
          { name: history.displayName, points: history.points.map(p => ({ at: p.time, value: p.value })) }
        ]));
    }
  }

  /**
   * Liest den Zustand frisch und toggelt nur, wenn er der Absicht widerspricht
   * und eindeutig `on`/`off` ist: der Toggle-Endpunkt schaltet alles ausser
   * `on` EIN, ein Ausschalten auf einem toten Geraet wuerde also einschalten.
   *
   * `guard` (nur beim Ausschalten einer Maschine): der Lauf-Helfer kommt aus
   * demselben frischen Abruf (manuelle INPUT_BOOLEAN stehen in `view=all`). Hat
   * das Feld nicht gefragt, weil sein bis zu 30 s alter Stand „läuft nicht“ sagte,
   * der Helfer aber jetzt `on` ist, wird nicht geschaltet: `needs-confirm` laesst
   * das Feld den Bestaetigungsdialog nachholen.
   */
  setSwitch(entityId: string, on: boolean, guard?: SwitchGuard): Observable<SwitchOutcome> {
    return this.switches.getSwitches(undefined, 'all').pipe(
      switchMap(list => {
        const state = list.find(s => s.entityId === entityId)?.state;
        if (state !== 'on' && state !== 'off') {
          return of<SwitchOutcome>('unavailable');
        }
        if ((state === 'on') === on) {
          return of<SwitchOutcome>('already');
        }
        if (!on && guard && !guard.confirmed
          && list.find(s => s.entityId === guard.runningEntityId)?.state === 'on') {
          return of<SwitchOutcome>('needs-confirm');
        }
        return this.switches.toggle(entityId).pipe(map((): SwitchOutcome => 'switched'));
      })
    );
  }

  /** Klemmt auf 5..30 und rundet auf 0,5 (Bedienhilfe; das Backend validiert verbindlich). */
  setTarget(thermostatId: number, target: number): Observable<void> {
    return this.thermostats.setTarget(thermostatId, clampTarget(target)).pipe(map(() => undefined));
  }

  /**
   * Liest den Schaltzustand frisch und schreibt die Helligkeit nur, wenn die Lampe
   * genau `on` ist: `set_device_info`/`transition_light_state` schalten eine
   * ausgeschaltete Lampe sonst ein.
   */
  setBrightness(entityId: string, brightness: number): Observable<BrightnessOutcome> {
    return this.writeLight(entityId, { brightness });
  }

  /** Wie `setBrightness`: frisch lesen, nur bei `on` schreiben (sonst schaltet das Geraet ein). */
  setLightColor(entityId: string, request: LightColorRequest): Observable<BrightnessOutcome> {
    return this.writeLight(entityId, request);
  }

  private writeLight(entityId: string, state: LightStateRequest): Observable<BrightnessOutcome> {
    return this.switches.getSwitches(undefined, 'all').pipe(
      switchMap(list => list.find(s => s.entityId === entityId)?.state !== 'on'
        ? of<BrightnessOutcome>('lamp-off')
        : this.findDevice(entityId).pipe(
            switchMap(device => device
              ? this.smartDevices.setLightState(device.id, state)
              : throwError(() => new Error(`Kein Gerät für ${entityId} gefunden.`))),
            tap(() => this.deviceList = null),
            map((): BrightnessOutcome => 'set')))
    );
  }

  /** Liest den Schlosszustand frisch; es gibt bewusst nur Verriegeln. */
  lockDoor(smartlockId: number): Observable<ActionOutcome> {
    return this.nuki.getLocks().pipe(switchMap(locks => {
      const lock = locks.find(l => l.smartlockId === smartlockId);
      if (!lock) {
        return of<ActionOutcome>('unavailable');
      }
      if (lock.state === 'locked') {
        return of<ActionOutcome>('already');
      }
      return this.nuki.sendAction(smartlockId, 'LOCK').pipe(map((): ActionOutcome => 'done'));
    }));
  }

  setCameraArmed(cameraId: string, armed: boolean): Observable<ActionOutcome> {
    return this.blink.getCameras().pipe(switchMap(cameras => {
      const camera = cameras.find(c => c.cameraId === cameraId);
      if (!camera) {
        return of<ActionOutcome>('unavailable');
      }
      if (camera.armed === armed) {
        return of<ActionOutcome>('already');
      }
      return this.blink.setCameraArmed(cameraId, armed).pipe(map((): ActionOutcome => 'done'));
    }));
  }

  takeSnapshot(cameraId: string): Observable<void> {
    return this.blink.takeSnapshot(cameraId).pipe(map(() => undefined));
  }

  thumbnailUrl(cameraId: string, cacheKey: number): string {
    return this.blink.thumbnailUrl(cameraId, cacheKey);
  }

  setThermostatMode(thermostatId: number, mode: ThermostatMode): Observable<ActionOutcome> {
    return this.thermostats.list().pipe(switchMap(list => {
      const thermostat = list.find(t => t.id === thermostatId);
      if (!thermostat) {
        return of<ActionOutcome>('unavailable');
      }
      if (thermostat.mode === mode) {
        return of<ActionOutcome>('already');
      }
      return this.thermostats.setMode(thermostatId, mode).pipe(map((): ActionOutcome => 'done'));
    }));
  }

  /** null = kein Smart-Device zu dieser Entity-ID. */
  lightDevice(entityId: string): Observable<LightExtras | null> {
    return this.findDevice(entityId).pipe(map(device => device
      ? {
          dimmable: device.capabilities.includes('BRIGHTNESS'),
          brightness: device.brightness ?? null,
          supportsColor: device.capabilities.includes('COLOR'),
          supportsColorTemp: device.capabilities.includes('COLOR_TEMP'),
          hue: device.hue ?? null,
          saturation: device.saturation ?? null,
          colorTemp: device.colorTemp ?? null,
          colorTempRange: colorTempRange(device.metadata)
        }
      : null));
  }

  /**
   * Zusatzdaten fuer das Detail-Feld. Jede Quelle faengt ihren Fehler selbst: eine
   * gescheiterte Quelle fehlt im Ergebnis, das Observable selbst scheitert nie.
   */
  loadExtras(device: HouseDevice): Observable<Partial<DetailExtras>> {
    switch (device.kind) {
      case 'switch':
        return this.part(this.lightDevice(device.entityId), light => ({ light }));
      case 'thermostat': {
        const thermostatId = thermostatIdOf(device.target);
        return thermostatId === null ? of({}) : this.thermostatExtras(thermostatId);
      }
      case 'door': {
        const smartlockId = smartlockIdOf(device.lockEntityId);
        return smartlockId === null ? of({}) : this.part(this.lockExtras(smartlockId), lock => ({ lock }));
      }
      case 'camera': {
        const cameraId = cameraIdOf(device.armedEntityId);
        return cameraId === null ? of({}) : this.part(
          this.blink.getCameras().pipe(map(list => list.find(c => c.cameraId === cameraId) ?? null)),
          camera => ({ camera }));
      }
      case 'waste':
        return this.part(this.waste.getUpcoming(), waste => ({ waste }));
      case 'pet':
        return forkJoin([
          this.part(this.petExtras(trackerIdOf(device.homeEntityId)), pet => ({ pet })),
          this.part(this.petSupplies.getSupplies(), supplies => ({ supplies }))
        ]).pipe(map(([pet, supplies]) => ({ ...pet, ...supplies })));
      default:
        return of({});
    }
  }

  /** Eine Quelle als Teil-Ergebnis; Fehler ⇒ leeres Teil-Ergebnis. */
  private part<T>(request: Observable<T>, toPart: (value: T) => Partial<DetailExtras>): Observable<Partial<DetailExtras>> {
    return request.pipe(map(toPart), catchError(() => of<Partial<DetailExtras>>({})));
  }

  private lockExtras(smartlockId: number): Observable<LockExtras | null> {
    return this.nuki.getLocks().pipe(map(locks => {
      const lock = locks.find(l => l.smartlockId === smartlockId);
      return lock ? { smartlockId, batteryCharge: lock.batteryCharge, batteryCritical: lock.batteryCritical } : null;
    }));
  }

  private petExtras(trackerId: string | null): Observable<PetExtras> {
    return this.tractive.getPets().pipe(switchMap(pets => {
      const pet = trackerId === null ? null
        : pets.find(p => p.trackerId.toLowerCase() === trackerId.toLowerCase()) ?? null;
      if (!pet) {
        return of<PetExtras>({ pet: null, lastWalk: null });
      }
      return this.tractive.getWalks(pet.trackerId, 7).pipe(map(walks => ({
        pet,
        lastWalk: walks.reduce<typeof walks[number] | null>((latest, walk) =>
          latest === null || walk.end > latest.end ? walk : latest, null)
      })));
    }));
  }

  /** Raumwert und Modus aus EINEM `thermostats.list()`. */
  private thermostatExtras(thermostatId: number): Observable<Partial<DetailExtras>> {
    return this.thermostats.list().pipe(
      switchMap(list => {
        const thermostatMode = list.find(t => t.id === thermostatId)?.mode ?? null;
        return this.roomValue(list, thermostatId).pipe(
          map((roomValue): Partial<DetailExtras> => ({ roomValue, thermostatMode })),
          catchError(() => of<Partial<DetailExtras>>({ thermostatMode })));
      }),
      catchError(() => of<Partial<DetailExtras>>({})));
  }

  /**
   * Helligkeit (Prozent) je Schalter-Entity, nur fuer dimmbare Geraete mit bekanntem Wert -
   * fuer den Lichtschein der Szene. Laedt immer frisch und aktualisiert dabei den Cache.
   */
  lampBrightness(): Observable<Map<string, number>> {
    return this.smartDevices.getAllDevices().pipe(
      tap(list => this.deviceList = list),
      map(list => new Map(list
        .filter(d => d.entityId && d.capabilities.includes('BRIGHTNESS') && d.brightness != null)
        .map(d => [d.entityId!, d.brightness!] as const))));
  }

  /** Raumwert eines Thermostats; null ohne zugeordneten Raumfuehler oder unbekanntes Thermostat. */
  thermostatRoom(thermostatId: number): Observable<BubbleValue | null> {
    return this.thermostats.list().pipe(switchMap(list => this.roomValue(list, thermostatId)));
  }

  private roomValue(list: Thermostat[], thermostatId: number): Observable<BubbleValue | null> {
    const thermostat = list.find(t => t.id === thermostatId);
    if (!thermostat?.roomSensorId) {
      return of(null);
    }
    return this.temperatures.getCurrent().pipe(map((readings: CurrentTemperatureReading[]) => {
      const room = roomReading(thermostat, readings, Date.now());
      if (!room) {
        return null;
      }
      return room.valueText === '–' ? MISSING : { text: room.valueText + '°', stale: room.stale };
    }));
  }

  private temperatureHistory(entityId: string, range: HistoryRange): Observable<HistorySeries[] | null> {
    return this.resolveSensorId(entityId).pipe(switchMap(sensorId => sensorId === null
      ? of(null)
      : this.temperatures.getSensorSeries(sensorId, range).pipe(map(series => [
          { name: series.name, points: series.temperature.map(p => ({ at: p.time, value: p.value })) }
        ]))));
  }

  /**
   * Zuerst `/current`, sonst die Serienliste (sie enthaelt auch gerade unerreichbare Fuehler).
   * Gemerkt wird nur ein Treffer: ein Fuehler, der nach einem Ausfall zurueckkommt, soll seinen
   * Verlauf ohne Neuladen der Seite bekommen.
   */
  private resolveSensorId(entityId: string): Observable<string | null> {
    const cached = this.sensorIds.get(entityId);
    if (cached !== undefined) {
      return of(cached);
    }
    return this.temperatures.getCurrent().pipe(
      switchMap(readings => {
        const hit = readings.find(r => r.entityId === entityId);
        if (hit) {
          return of(hit.sensorId);
        }
        return this.temperatures.getSeries('DAY').pipe(
          map(series => series.find(s => s.entityId === entityId)?.sensorId ?? null));
      }),
      tap(sensorId => {
        if (sensorId !== null) {
          this.sensorIds.set(entityId, sensorId);
        }
      })
    );
  }

  private findDevice(entityId: string): Observable<SmartDevice | undefined> {
    const list$ = this.deviceList
      ? of(this.deviceList)
      : this.smartDevices.getAllDevices().pipe(tap(list => this.deviceList = list));
    return list$.pipe(map(list => list.find(d => d.entityId === entityId)));
  }
}
