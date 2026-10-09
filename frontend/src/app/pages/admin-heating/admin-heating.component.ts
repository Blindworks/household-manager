import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ThermostatService } from '../../services/thermostat.service';
import { Thermostat } from '../../models/thermostat.model';

/**
 * Admin-Seite „Heizung": Hub-IP eintragen und pruefen, gefundene Thermostate
 * umbenennen. Die Hub-IP selbst liefert das Backend nicht zurueck, das Feld
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

  readonly thermostats = signal<Thermostat[]>([]);
  readonly searching = signal(false);
  readonly renamingId = signal<number | null>(null);
  readonly errorMessage = signal<string | null>(null);

  ip = '';
  /** Bearbeiteter Name je Thermostat-Id. */
  names: Record<number, string> = {};

  ngOnInit(): void {
    this.api.list().subscribe({
      next: list => this.show(list),
      error: error => this.errorMessage.set(this.messageFrom(error))
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
      next: list => {
        this.show(list);
        this.searching.set(false);
      },
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
