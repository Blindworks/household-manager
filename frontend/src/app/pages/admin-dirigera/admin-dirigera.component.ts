import { Component, NgZone, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DirigeraService } from '../../services/dirigera.service';
import { DirigeraHub, DirigeraPairingStatus, DirigeraSensor } from '../../models/dirigera.model';

const POLL_MS = 2000;
const COUNTDOWN_MS = 1000;

type SensorStatus = 'active' | 'unreachable' | 'gone';

/**
 * Admin-Seite „IKEA Dirigera“: Hub-IP, Kopplung per Aktionsknopf, Sensorliste.
 * Die Restzeit der Kopplung rechnet gegen die Uhr dieses Browsers (expiresAt kommt vom Server).
 */
@Component({
  selector: 'app-admin-dirigera',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-dirigera.component.html',
  styleUrl: './admin-dirigera.component.scss'
})
export class AdminDirigeraComponent implements OnInit, OnDestroy {
  private readonly api = inject(DirigeraService);
  private readonly zone = inject(NgZone);

  readonly hub = signal<DirigeraHub | null>(null);
  readonly pairing = signal<DirigeraPairingStatus | null>(null);
  readonly sensors = signal<DirigeraSensor[]>([]);
  readonly secondsLeft = signal(0);
  readonly busy = signal(false);
  readonly errorMessage = signal<string | null>(null);

  hostInput = '';

  private pollTimer?: ReturnType<typeof setInterval>;
  private countdownTimer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.loadHub(true);
    this.loadSensors();
    // Eine laufende Kopplung (Seite neu geladen) wird wieder aufgenommen.
    this.api.getPairing().subscribe({
      next: status => this.applyPairing(status),
      error: () => { /* Status ist nur eine Zugabe */ }
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  saveHost(): void {
    const host = this.hostInput.trim();
    if (!host) { return; }
    this.run(this.api.updateHost(host), hub => this.hub.set(hub));
  }

  startPairing(): void {
    this.run(this.api.startPairing(), status => this.applyPairing(status));
  }

  unpair(): void {
    if (!confirm('Die Kopplung mit dem Dirigera-Hub wirklich aufheben?')) { return; }
    this.run(this.api.unpair(), () => {
      this.pairing.set(null);
      this.loadHub(false);
    });
  }

  remove(sensor: DirigeraSensor): void {
    // Regel confirmToggle: gegen die aktuelle Liste aufloesen
    const current = this.sensors().find(s => s.id === sensor.id);
    if (!current || current.knownToHub) { return; }
    if (!confirm(`Sensor „${current.name ?? current.id}“ samt Historie endgültig löschen?`)) { return; }
    this.run(this.api.deleteSensor(current.id),
      () => this.sensors.update(list => list.filter(s => s.id !== current.id)));
  }

  status(sensor: DirigeraSensor): SensorStatus {
    if (!sensor.knownToHub) { return 'gone'; }
    return sensor.reachable ? 'active' : 'unreachable';
  }

  statusLabel(sensor: DirigeraSensor): string {
    switch (this.status(sensor)) {
      case 'active': return 'Aktiv';
      case 'unreachable': return 'Nicht erreichbar';
      case 'gone': return 'Nicht mehr am Hub';
    }
  }

  /** Kontakte haben statt Temperatur/Feuchte nur offen/zu; null wird nie als "zu" geraten. */
  contactLabel(sensor: DirigeraSensor): string {
    if (sensor.open === null) { return 'Zustand unbekannt'; }
    return sensor.open ? 'Offen' : 'Geschlossen';
  }

  private applyPairing(status: DirigeraPairingStatus): void {
    this.pairing.set(status);
    if (status.state === 'PRESS_BUTTON') {
      this.updateCountdown(status);
      this.startPolling();
      return;
    }
    const wasPolling = this.pollTimer !== undefined;
    this.stopPolling();
    if (wasPolling) {
      this.loadHub(false);
      this.loadSensors();
    }
  }

  private startPolling(): void {
    if (this.pollTimer !== undefined) { return; }
    // Ausserhalb der Zone, sonst wird die Anwendung wegen der Dauer-Timer nie stabil.
    this.zone.runOutsideAngular(() => {
      this.pollTimer = setInterval(() => this.zone.run(() => this.poll()), POLL_MS);
      this.countdownTimer = setInterval(() => this.zone.run(() => {
        const current = this.pairing();
        if (current) { this.updateCountdown(current); }
      }), COUNTDOWN_MS);
    });
  }

  private stopPolling(): void {
    clearInterval(this.pollTimer);
    clearInterval(this.countdownTimer);
    this.pollTimer = undefined;
    this.countdownTimer = undefined;
  }

  private poll(): void {
    this.api.getPairing().subscribe({
      next: status => this.applyPairing(status),
      error: () => { /* naechster Takt versucht es erneut */ }
    });
  }

  private updateCountdown(status: DirigeraPairingStatus): void {
    const end = status.expiresAt ? Date.parse(status.expiresAt) : NaN;
    this.secondsLeft.set(Number.isNaN(end) ? 0 : Math.max(0, Math.ceil((end - Date.now()) / 1000)));
  }

  private loadHub(initial: boolean): void {
    this.api.getHub().subscribe({
      next: hub => {
        this.hub.set(hub);
        if (initial || !this.hostInput) { this.hostInput = hub.host ?? ''; }
      },
      error: err => this.errorMessage.set(this.messageOf(err))
    });
  }

  private loadSensors(): void {
    this.api.getSensors().subscribe({
      next: list => this.sensors.set(list),
      error: err => this.errorMessage.set(this.messageOf(err))
    });
  }

  private run<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
    this.busy.set(true);
    this.errorMessage.set(null);
    request.subscribe({
      next: value => { this.busy.set(false); onSuccess(value); },
      error: (err: HttpErrorResponse) => { this.busy.set(false); this.errorMessage.set(this.messageOf(err)); }
    });
  }

  private messageOf(err: HttpErrorResponse): string {
    return err.error?.message ?? 'Die Aktion ist fehlgeschlagen.';
  }
}
