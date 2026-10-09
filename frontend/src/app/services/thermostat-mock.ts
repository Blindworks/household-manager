import { Observable, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Thermostat,
  ThermostatHistoryResponse,
  ThermostatMode,
  ThermostatRange
} from '../models/thermostat.model';

/** Zieltemperatur des Modus OFF (das Geraet kennt kein echtes Aus). */
const OFF_TARGET = 5;

/**
 * Datenquelle im Speicher: liefert denselben Vertrag wie die REST-API
 * `/v1/thermostats`, solange das Backend nicht verdrahtet ist. Fuenf Raeume
 * decken alle Zustaende der Kachel ab (heizt, Frostschutz, offline,
 * Batterie niedrig, aus).
 */
export class ThermostatMock {
  private thermostats: Thermostat[] = [
    mock(1, 'Wohnzimmer', { currentTemp: 20.4, targetTemp: 22, mode: 'MANUAL', heating: true }),
    mock(2, 'Schlafzimmer', { currentTemp: 16.8, targetTemp: 7, mode: 'FROST_PROTECTION', heating: false }),
    mock(3, 'Küche', { online: false, currentTemp: null, targetTemp: 20, mode: 'MANUAL', heating: false, lastUpdated: '2026-10-09T09:12:00' }),
    mock(4, 'Bad', { currentTemp: 22.1, targetTemp: 23, mode: 'MANUAL', heating: true, batteryLow: true }),
    mock(5, 'Büro', { currentTemp: 18.2, targetTemp: OFF_TARGET, mode: 'OFF', heating: false })
  ];

  list(): Observable<Thermostat[]> {
    return of(this.thermostats.map(t => ({ ...t })));
  }

  setTarget(id: number, targetTemp: number): Observable<Thermostat> {
    const thermostat = this.writable(id);
    if (thermostat instanceof HttpErrorResponse) {
      return throwError(() => thermostat);
    }
    thermostat.targetTemp = targetTemp;
    thermostat.mode = targetTemp <= OFF_TARGET ? 'OFF' : 'MANUAL';
    this.refresh(thermostat);
    return of({ ...thermostat });
  }

  setMode(id: number, mode: ThermostatMode): Observable<Thermostat> {
    const thermostat = this.writable(id);
    if (thermostat instanceof HttpErrorResponse) {
      return throwError(() => thermostat);
    }
    thermostat.mode = mode;
    if (mode === 'OFF') {
      thermostat.targetTemp = OFF_TARGET;
    } else if (mode === 'MANUAL' && (thermostat.targetTemp ?? 0) <= OFF_TARGET) {
      thermostat.targetTemp = 18;
    }
    this.refresh(thermostat);
    return of({ ...thermostat });
  }

  /** `ids` null = alle. Offline-Thermostate bleiben unberuehrt. */
  bulk(ids: number[] | null, targetTemp: number): Observable<Thermostat[]> {
    this.thermostats
      .filter(t => t.online && (ids === null || ids.includes(t.id)))
      .forEach(t => {
        t.targetTemp = targetTemp;
        t.mode = 'MANUAL';
        this.refresh(t);
      });
    return this.list();
  }

  history(id: number, range: ThermostatRange): Observable<ThermostatHistoryResponse> {
    const thermostat = this.thermostats.find(t => t.id === id);
    const steps = range === 'DAY' ? 96 : 168;
    const stepMinutes = range === 'DAY' ? 15 : 60;
    const now = new Date(2026, 9, 9, 18, 0, 0);
    const base = thermostat?.currentTemp ?? 19;
    const points = Array.from({ length: steps }, (_, i) => {
      const time = new Date(now.getTime() - (steps - 1 - i) * stepMinutes * 60_000);
      const hour = time.getHours() + time.getMinutes() / 60;
      const comfort = hour >= 6 && hour < 22;
      const targetTemp = comfort ? Math.max(base + 1, 20) : 17;
      const currentTemp = round1(base + Math.sin((i / steps) * Math.PI * (range === 'DAY' ? 2 : 14)) * 1.2);
      return {
        time: toLocalIso(time),
        currentTemp: thermostat && !thermostat.online && i > steps * 0.8 ? null : currentTemp,
        targetTemp,
        heating: currentTemp < targetTemp - 0.3
      };
    });
    return of({ points });
  }

  /** Leere oder keine IPv4-Adresse -> 400 wie das Backend; sonst die Liste. */
  setHub(ip: string): Observable<Thermostat[]> {
    const trimmed = ip.trim();
    const valid = /^d{1,3}(.d{1,3}){3}$/.test(trimmed)
      && trimmed.split('.').every(part => Number(part) <= 255);
    if (!valid) {
      return throwError(() => new HttpErrorResponse({
        status: 400, error: { message: 'Ungültige IP-Adresse.' }
      }));
    }
    return this.list();
  }

  rename(id: number, name: string): Observable<Thermostat> {
    const thermostat = this.thermostats.find(t => t.id === id);
    if (!thermostat) {
      return throwError(() => new HttpErrorResponse({
        status: 404, error: { message: 'Thermostat nicht gefunden.' }
      }));
    }
    if (!name.trim()) {
      return throwError(() => new HttpErrorResponse({
        status: 400, error: { message: 'Der Name darf nicht leer sein.' }
      }));
    }
    thermostat.name = name.trim();
    return of({ ...thermostat });
  }

  private writable(id: number): Thermostat | HttpErrorResponse {
    const thermostat = this.thermostats.find(t => t.id === id);
    if (!thermostat) {
      return new HttpErrorResponse({ status: 404, error: { message: 'Thermostat nicht gefunden.' } });
    }
    if (!thermostat.online) {
      return new HttpErrorResponse({ status: 502, error: { message: 'Hub nicht erreichbar.' } });
    }
    return thermostat;
  }

  private refresh(thermostat: Thermostat): void {
    thermostat.heating =
      thermostat.mode !== 'OFF' &&
      thermostat.currentTemp !== null &&
      thermostat.targetTemp !== null &&
      thermostat.currentTemp < thermostat.targetTemp - 0.2;
    thermostat.lastUpdated = toLocalIso(new Date());
  }
}

function mock(id: number, name: string, overrides: Partial<Thermostat>): Thermostat {
  return {
    id,
    name,
    online: true,
    currentTemp: 20,
    targetTemp: 20,
    mode: 'MANUAL',
    heating: false,
    batteryLow: false,
    lastUpdated: '2026-10-09T18:00:00',
    ...overrides
  };
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function toLocalIso(date: Date): string {
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
