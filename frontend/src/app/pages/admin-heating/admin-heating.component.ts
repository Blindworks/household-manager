import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ThermostatService } from '../../services/thermostat.service';
import { Thermostat } from '../../models/thermostat.model';
import { TemperatureService } from '../../services/temperature.service';
import { CurrentTemperatureReading } from '../../models/temperature.model';

/** Auswahl eines Raumfuehlers; die DWD-Vorhersage ist keiner. */
export interface RoomSensorOption {
  sensorId: string;
  label: string;
}

const WEATHER_SENSOR_ID = 'weather:outdoor';

/**
 * Admin-Seite „Heizung": Hub-IP eintragen und pruefen, gefundene Thermostate
 * umbenennen und ihnen den Temperaturfuehler ihres Raums zuordnen. Die Hub-IP selbst liefert das Backend nicht zurueck, das Feld
 * startet deshalb leer.
 */
@Component({
  selector: 'app-admin-heating',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './admin-heating.component.html',
  styleUrl: './admin-heating.component.scss'
})
export class AdminHeatingComponent implements OnInit {
  private readonly api = inject(ThermostatService);
  private readonly temperatureService = inject(TemperatureService);

  readonly thermostats = signal<Thermostat[]>([]);
  readonly searching = signal(false);
  readonly renamingId = signal<number | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly sensorOptions = signal<RoomSensorOption[]>([]);
  readonly assigningId = signal<number | null>(null);

  ip = '';
  /** Bearbeiteter Name je Thermostat-Id. */
  names: Record<number, string> = {};

  ngOnInit(): void {
    this.api.list().subscribe({
      next: list => this.show(list),
      error: error => this.errorMessage.set(this.messageFrom(error))
    });
    this.temperatureService.getCurrent().subscribe({
      next: readings => this.sensorOptions.set(toSensorOptions(readings)),
      error: () => this.errorMessage.set('Temperaturfühler konnten nicht geladen werden.')
    });
  }

  /**
   * Optionen fuer ein Thermostat: ein zugeordneter, gerade nicht gelisteter Fuehler bleibt
   * waehlbar und als solcher erkennbar - sonst zeigte die Auswahl still „Kein Raumfühler".
   */
  optionsFor(thermostat: Thermostat): RoomSensorOption[] {
    const options = this.sensorOptions();
    const assigned = thermostat.roomSensorId;
    if (assigned && !options.some(option => option.sensorId === assigned)) {
      return [...options, { sensorId: assigned, label: `${assigned} (nicht gefunden)` }];
    }
    return options;
  }

  assignRoomSensor(thermostat: Thermostat, select: HTMLSelectElement): void {
    const normalized = select.value === '' ? null : select.value;
    if (normalized === thermostat.roomSensorId || this.assigningId() !== null) {
      return;
    }
    this.errorMessage.set(null);
    this.assigningId.set(thermostat.id);
    this.api.setRoomSensor(thermostat.id, normalized).subscribe({
      next: updated => {
        this.thermostats.update(list => list.map(t => (t.id === updated.id ? updated : t)));
        this.assigningId.set(null);
      },
      error: error => {
        this.errorMessage.set(this.messageFrom(error));
        // Auswahl zurueck auf den gespeicherten Stand - die Bindungen aendern sich nicht,
        // Angular setzte das Feld deshalb nicht selbst zurueck.
        select.value = thermostat.roomSensorId ?? '';
        this.assigningId.set(null);
      }
    });
  }

  get canSearch(): boolean {
    return this.ip.trim().length > 0 && !this.searching();
  }

  searchHub(): void {
    if (!this.canSearch) {
      return;
    }
    this.errorMessage.set(null);
    this.searching.set(true);
    this.api.setHub(this.ip.trim()).subscribe({
      // Die Antwort enthaelt nur die Thermostate dieses Hubs; angezeigt wird die Gesamtliste.
      next: () => this.api.list().subscribe({
        next: list => {
          this.show(list);
          this.searching.set(false);
        },
        error: error => {
          this.errorMessage.set(this.messageFrom(error));
          this.searching.set(false);
        }
      }),
      error: error => {
        this.errorMessage.set(this.messageFrom(error));
        this.searching.set(false);
      }
    });
  }

  canRename(thermostat: Thermostat): boolean {
    const name = (this.names[thermostat.id] ?? '').trim();
    return name.length > 0 && name !== thermostat.name && this.renamingId() === null;
  }

  rename(thermostat: Thermostat): void {
    if (!this.canRename(thermostat)) {
      return;
    }
    this.errorMessage.set(null);
    this.renamingId.set(thermostat.id);
    this.api.rename(thermostat.id, this.names[thermostat.id].trim()).subscribe({
      next: updated => {
        this.thermostats.update(list => list.map(t => (t.id === updated.id ? updated : t)));
        this.names[updated.id] = updated.name;
        this.renamingId.set(null);
      },
      error: error => {
        this.errorMessage.set(this.messageFrom(error));
        this.renamingId.set(null);
      }
    });
  }

  private show(list: Thermostat[]): void {
    this.thermostats.set(list);
    this.names = Object.fromEntries(list.map(t => [t.id, t.name]));
  }

  private messageFrom(error: HttpErrorResponse): string {
    return error.error?.message ?? 'Fehler bei der Netzwerk-Kommunikation.';
  }
}

function toSensorOptions(readings: CurrentTemperatureReading[]): RoomSensorOption[] {
  return readings
    .filter(reading => reading.sensorId !== WEATHER_SENSOR_ID)
    .map(reading => ({ sensorId: reading.sensorId, label: reading.name }))
    .sort((a, b) => a.label.localeCompare(b.label, 'de'));
}
