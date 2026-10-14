import { Component, NgZone, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BleSensorService } from '../../services/ble-sensor.service';
import { BleSensorAdminResponse } from '../../models/ble-sensor.model';

/** Refresh-Takt: beim Rollout erkennt man am steigenden Wert, welches Thermometer man in der Hand hat. */
const REFRESH_MS = 30_000;

type SensorStatus = 'active' | 'inactive' | 'unadopted';

/**
 * Admin-Seite „Bluetooth-Thermometer“: Uebernehmen (Name vergeben), Umbenennen, Deaktivieren,
 * Loeschen (nur inaktiv). Nicht uebernommene Sensoren stehen oben — die Liste kommt vom Server
 * bereits in dieser Reihenfolge.
 */
@Component({
  selector: 'app-admin-ble-sensors',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-ble-sensors.component.html',
  styleUrl: './admin-ble-sensors.component.scss'
})
export class AdminBleSensorsComponent implements OnInit, OnDestroy {
  private readonly api = inject(BleSensorService);
  private readonly zone = inject(NgZone);

  readonly sensors = signal<BleSensorAdminResponse[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  /** Id des Sensors, dessen Namenszeile gerade offen ist (Uebernehmen oder Umbenennen). */
  editingId: number | null = null;
  editingName = '';

  private refreshTimer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.load(true);
    // Ausserhalb der Zone, sonst bleibt die Anwendung (und whenStable) wegen des Dauer-Timers nie stabil.
    this.refreshTimer = this.zone.runOutsideAngular(() =>
      setInterval(() => this.zone.run(() => this.load(false)), REFRESH_MS)
    );
  }

  ngOnDestroy(): void {
    clearInterval(this.refreshTimer);
  }

  status(sensor: BleSensorAdminResponse): SensorStatus {
    if (sensor.active) { return 'active'; }
    return sensor.name ? 'inactive' : 'unadopted';
  }

  statusLabel(sensor: BleSensorAdminResponse): string {
    switch (this.status(sensor)) {
      case 'active': return 'Aktiv';
      case 'inactive': return 'Inaktiv';
      case 'unadopted': return 'Nicht übernommen';
    }
  }

  displayName(sensor: BleSensorAdminResponse): string {
    return sensor.name ?? sensor.mac;
  }

  startEdit(sensor: BleSensorAdminResponse): void {
    this.editingId = sensor.id;
    this.editingName = sensor.name ?? '';
    this.errorMessage.set(null);
  }

  cancelEdit(): void {
    this.editingId = null;
    this.editingName = '';
  }

  /** Uebernehmen und Umbenennen: beides setzt active=true mit dem eingegebenen Namen. */
  saveName(): void {
    const id = this.editingId;
    const name = this.editingName.trim();
    if (id === null || !name) { return; }
    // Regel confirmToggle: gegen die aktuelle Liste aufloesen, der Refresh laeuft weiter
    const current = this.sensors().find(s => s.id === id);
    if (!current) { this.cancelEdit(); return; }
    this.mutate(this.api.update(id, { name, active: true }), updated => {
      this.replace(updated);
      this.cancelEdit();
    });
  }

  deactivate(sensor: BleSensorAdminResponse): void {
    const current = this.sensors().find(s => s.id === sensor.id);
    if (!current || !current.active) { return; }
    this.mutate(this.api.update(current.id, { name: current.name, active: false }), updated => this.replace(updated));
  }

  remove(sensor: BleSensorAdminResponse): void {
    const current = this.sensors().find(s => s.id === sensor.id);
    if (!current || current.active) { return; }
    if (!confirm(`Sensor „${this.displayName(current)}“ samt Historie endgültig löschen?`)) { return; }
    this.mutate(this.api.delete(current.id), () => this.sensors.update(list => list.filter(s => s.id !== current.id)));
  }

  private load(initial: boolean): void {
    this.api.list().subscribe({
      next: list => {
        this.sensors.set(list);
        this.loading.set(false);
        this.loadFailed.set(false);
      },
      error: () => {
        // Nur der Erstabruf meldet; ein fehlgeschlagener Refresh behaelt den letzten Stand.
        if (initial) {
          this.loading.set(false);
          this.loadFailed.set(true);
        }
      }
    });
  }

  private mutate<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
    this.saving.set(true);
    this.errorMessage.set(null);
    request.subscribe({
      next: value => { this.saving.set(false); onSuccess(value); },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(err.error?.message ?? 'Die Aktion ist fehlgeschlagen.');
      }
    });
  }

  private replace(updated: BleSensorAdminResponse): void {
    this.sensors.update(list => list.map(s => (s.id === updated.id ? updated : s)));
  }
}
