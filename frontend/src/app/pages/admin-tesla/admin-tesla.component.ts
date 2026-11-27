import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { TeslaService } from '../../services/tesla.service';
import { TeslaSettings, TeslaStatus, TessieVehicle } from '../../models/tesla.model';
import { teslaRangeLabel } from '../../shared/tesla-view.util';

/**
 * Admin-Seite „Tesla“: Tessie-Token, Fahrzeug und Abrufintervall. Das Token wird nie
 * angezeigt; ein leeres Feld lässt es unverändert, „Token entfernen“ löscht es.
 */
@Component({
  selector: 'app-admin-tesla',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './admin-tesla.component.html',
  styleUrl: './admin-tesla.component.scss'
})
export class AdminTeslaComponent implements OnInit {
  private readonly api = inject(TeslaService);

  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  readonly messageType = signal<'success' | 'error' | null>(null);

  settings: TeslaSettings | null = null;
  tokenInput = '';
  vin = '';
  pollIntervalMinutes = 5;
  /** leer = Bild aus Teslas Konfigurator */
  imageUrl = '';
  vehicles: TessieVehicle[] = [];
  testResult: TeslaStatus | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.api.getSettings().subscribe({
      next: settings => {
        this.adopt(settings);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
      }
    });
  }

  /** Fahrzeuge des Kontos: mit dem eingegebenen Token, sonst dem gespeicherten. */
  loadVehicles(): void {
    this.run(() => this.api.listVehicles(this.tokenInput.trim() || undefined), vehicles => {
      this.vehicles = vehicles;
      this.notify(vehicles.length ? `${vehicles.length} Fahrzeug(e) gefunden.` : 'Keine Fahrzeuge im Konto.', 'success');
    });
  }

  save(): void {
    const token = this.tokenInput.trim();
    this.run(() => this.api.saveSettings({
      apiToken: token || null,
      vin: this.vin || null,
      pollIntervalMinutes: this.pollIntervalMinutes,
      imageUrl: this.imageUrl.trim() || null
    }), settings => {
      this.adopt(settings);
      this.notify('Gespeichert.', 'success');
    });
  }

  removeToken(): void {
    this.run(() => this.api.saveSettings({
      apiToken: '',
      vin: this.vin || null,
      pollIntervalMinutes: this.pollIntervalMinutes,
      imageUrl: this.imageUrl.trim() || null
    }), settings => {
      this.adopt(settings);
      this.testResult = null;
      this.notify('Token entfernt.', 'success');
    });
  }

  testNow(): void {
    this.run(() => this.api.refresh(), status => {
      this.testResult = status;
      this.notify(status.vehicle ? 'Abruf erfolgreich.' : (status.error ?? 'Keine Daten.'),
        status.vehicle ? 'success' : 'error');
    });
  }

  vehicleLabel(vehicle: TessieVehicle): string {
    return vehicle.displayName ? `${vehicle.displayName} (${vehicle.vin})` : vehicle.vin;
  }

  rangeLabel(rangeKm: number | undefined): string {
    return teslaRangeLabel(rangeKm);
  }

  private adopt(settings: TeslaSettings): void {
    this.settings = settings;
    this.tokenInput = '';
    this.vin = settings.vin ?? '';
    this.pollIntervalMinutes = settings.pollIntervalMinutes;
    this.imageUrl = settings.imageUrl ?? '';
  }

  private run<T>(request: () => Observable<T>, onSuccess: (value: T) => void): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    request().subscribe({
      next: value => {
        this.busy.set(false);
        onSuccess(value);
      },
      error: (error: Error) => {
        this.busy.set(false);
        this.notify(error.message, 'error');
      }
    });
  }

  private notify(text: string, type: 'success' | 'error'): void {
    this.message.set(text);
    this.messageType.set(type);
  }
}
