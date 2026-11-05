import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of, throwError } from 'rxjs';
import { map, switchMap, tap } from 'rxjs/operators';
import { TemperatureService } from '../../services/temperature.service';
import { ThermostatService } from '../../services/thermostat.service';
import { PowerConsumerService } from '../../services/power-consumer.service';
import { SwitchService } from '../../services/switch.service';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SmartDevice } from '../../models/smart-device.model';
import { HistorySource } from '../../shared/house/device-detail.util';
import { BubbleValue, MISSING } from '../../shared/house/house-bubbles.util';
import { clampTarget, roomReading } from '../../shared/thermostat-view.util';

export interface HistoryPoint { at: string; value: number | null }
export interface HistorySeries { name: string; points: HistoryPoint[]; dashed?: boolean }
export type SwitchOutcome = 'switched' | 'already' | 'unavailable';
export type HistoryRange = 'DAY' | 'WEEK';
export interface LightInfo { dimmable: boolean; brightness: number | null }

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
   */
  setSwitch(entityId: string, on: boolean): Observable<SwitchOutcome> {
    return this.switches.getSwitches(undefined, 'all').pipe(
      switchMap(list => {
        const state = list.find(s => s.entityId === entityId)?.state;
        if (state !== 'on' && state !== 'off') {
          return of<SwitchOutcome>('unavailable');
        }
        if ((state === 'on') === on) {
          return of<SwitchOutcome>('already');
        }
        return this.switches.toggle(entityId).pipe(map((): SwitchOutcome => 'switched'));
      })
    );
  }

  /** Klemmt auf 5..30 und rundet auf 0,5 (Bedienhilfe; das Backend validiert verbindlich). */
  setTarget(thermostatId: number, target: number): Observable<void> {
    return this.thermostats.setTarget(thermostatId, clampTarget(target)).pipe(map(() => undefined));
  }

  setBrightness(entityId: string, brightness: number): Observable<void> {
    return this.findDevice(entityId).pipe(
      switchMap(device => device
        ? this.smartDevices.setLightState(device.id, { brightness })
        : throwError(() => new Error(`Kein Gerät für ${entityId} gefunden.`))),
      tap(() => this.deviceList = null),
      map(() => undefined)
    );
  }

  /** null = kein Smart-Device zu dieser Entity-ID. */
  lightDevice(entityId: string): Observable<LightInfo | null> {
    return this.findDevice(entityId).pipe(map(device => device
      ? { dimmable: device.capabilities.includes('BRIGHTNESS'), brightness: device.brightness ?? null }
      : null));
  }

  /** Raumwert eines Thermostats; null ohne zugeordneten Raumfuehler oder unbekanntes Thermostat. */
  thermostatRoom(thermostatId: number): Observable<BubbleValue | null> {
    return forkJoin([this.thermostats.list(), this.temperatures.getCurrent()]).pipe(map(([list, readings]) => {
      const thermostat = list.find(t => t.id === thermostatId);
      const room = thermostat ? roomReading(thermostat, readings, Date.now()) : null;
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
