import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { catchError, forkJoin, of } from 'rxjs';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SmartDevice } from '../../models/smart-device.model';

export type DeviceViewMode = 'normal' | 'compact';

/**
 * Wiederverwendbare Geraeteliste aus der smart_devices-Datenbank
 * mit Schalten, Status-Refresh und manuellem Rescan (gesamt oder je Typ).
 */
@Component({
  selector: 'app-smart-device-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './smart-device-list.component.html',
  styleUrl: './smart-device-list.component.scss'
})
export class SmartDeviceListComponent implements OnInit, OnDestroy {
  private static readonly VIEW_MODE_STORAGE_KEY = 'smartDeviceViewMode';
  private static readonly SUCCESS_MESSAGE_TIMEOUT_MS = 3000;

  private readonly smartDeviceService = inject(SmartDeviceService);
  private readonly typeOrder: ReadonlyArray<SmartDevice['deviceType']> = ['KASA', 'TAPO', 'MEROSS'];
  readonly scanTypes: ReadonlyArray<SmartDevice['deviceType']> = ['KASA', 'TAPO', 'MEROSS'];

  devices: SmartDevice[] = [];
  isLoading = true;
  isScanning = false;
  scanningType: SmartDevice['deviceType'] | 'ALL' | null = null;
  errorMessage: string | null = null;
  togglingDevices = new Set<number>();
  viewMode: DeviceViewMode = 'normal';

  // Kasa per IP hinzufuegen
  showAddKasaForm = false;
  kasaIpInput = '';
  isAddingKasaDevice = false;
  addKasaError: string | null = null;
  addKasaSuccessMessage: string | null = null;
  private addKasaSuccessTimeout: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.viewMode = this.readStoredViewMode();
    this.loadDevices();
  }

  ngOnDestroy(): void {
    this.clearAddKasaSuccessTimer();
  }

  setViewMode(mode: DeviceViewMode): void {
    if (this.viewMode === mode) {
      return;
    }
    this.viewMode = mode;
    this.persistViewMode(mode);
  }

  private readStoredViewMode(): DeviceViewMode {
    try {
      const stored = localStorage.getItem(SmartDeviceListComponent.VIEW_MODE_STORAGE_KEY);
      return stored === 'compact' ? 'compact' : 'normal';
    } catch {
      // localStorage nicht verfügbar (z. B. Private Mode) - Standard verwenden
      return 'normal';
    }
  }

  private persistViewMode(mode: DeviceViewMode): void {
    try {
      localStorage.setItem(SmartDeviceListComponent.VIEW_MODE_STORAGE_KEY, mode);
    } catch {
      // Persistenz optional - Fehler still ignorieren
    }
  }

  loadDevices(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.smartDeviceService.getAllDevices().subscribe({
      next: (devices) => {
        this.devices = devices;
        this.isLoading = false;

        // Automatisch den aktuellen Status aller Geräte im Hintergrund aktualisieren
        setTimeout(() => {
          this.refreshAllDevicesInBackground();
        }, 500);
      },
      error: (error: Error) => {
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  scanType(type: SmartDevice['deviceType']): void {
    this.isScanning = true;
    this.scanningType = type;
    this.errorMessage = null;
    this.smartDeviceService.scanDevices(type).subscribe({
      next: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      },
      error: (error: Error) => {
        this.errorMessage = `Rescan (${this.getTypeLabel(type)}) fehlgeschlagen: ${error.message}`;
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      }
    });
  }

  scanAllDeviceTypes(): void {
    this.isScanning = true;
    this.scanningType = 'ALL';
    this.errorMessage = null;

    const scanRequests = this.scanTypes.map(type =>
      this.smartDeviceService.scanDevices(type).pipe(
        catchError(() => of([] as SmartDevice[]))
      )
    );

    forkJoin(scanRequests).subscribe({
      next: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      },
      error: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      }
    });
  }

  toggleAddKasaForm(): void {
    if (this.showAddKasaForm) {
      this.cancelAddKasaForm();
      return;
    }
    this.showAddKasaForm = true;
    this.addKasaError = null;
    this.addKasaSuccessMessage = null;
    this.clearAddKasaSuccessTimer();
  }

  cancelAddKasaForm(): void {
    this.showAddKasaForm = false;
    this.kasaIpInput = '';
    this.addKasaError = null;
    this.addKasaSuccessMessage = null;
    this.clearAddKasaSuccessTimer();
  }

  submitAddKasaForm(): void {
    if (this.isAddingKasaDevice) {
      return;
    }

    const ip = this.kasaIpInput.trim();
    if (!ip) {
      this.addKasaError = 'Bitte eine IP-Adresse eingeben.';
      return;
    }
    if (!this.isValidIpv4(ip)) {
      this.addKasaError = 'Ungueltige IP-Adresse. Bitte z. B. 192.168.1.116 eingeben.';
      return;
    }

    this.isAddingKasaDevice = true;
    this.addKasaError = null;

    this.smartDeviceService.addKasaDeviceByIp(ip).subscribe({
      next: () => {
        this.isAddingKasaDevice = false;
        this.showAddKasaForm = false;
        this.kasaIpInput = '';
        this.loadDevices();
        this.showAddKasaSuccess('Kasa-Geraet wurde hinzugefuegt.');
      },
      error: (error: Error) => {
        // Eingabe bleibt bei einem Fehler erhalten, damit die IP nicht erneut getippt werden muss
        this.addKasaError = error.message;
        this.isAddingKasaDevice = false;
      }
    });
  }

  private isValidIpv4(value: string): boolean {
    const octets = value.split('.');
    if (octets.length !== 4) {
      return false;
    }
    return octets.every(octet => this.isValidIpv4Octet(octet));
  }

  private isValidIpv4Octet(octet: string): boolean {
    if (!/^\d{1,3}$/.test(octet)) {
      return false;
    }
    // Fuehrende Nullen (z. B. "010") wertet Java als Oktalzahl/Hostname statt als Dezimal-Oktett
    // und loest einen langsamen, verwirrenden DNS-Lookup statt eines klaren 400 aus - hier schon ablehnen.
    if (octet.length > 1 && octet.startsWith('0')) {
      return false;
    }
    return Number(octet) <= 255;
  }

  private showAddKasaSuccess(message: string): void {
    this.clearAddKasaSuccessTimer();
    this.addKasaSuccessMessage = message;
    this.addKasaSuccessTimeout = setTimeout(() => {
      this.addKasaSuccessMessage = null;
      this.addKasaSuccessTimeout = null;
    }, SmartDeviceListComponent.SUCCESS_MESSAGE_TIMEOUT_MS);
  }

  private clearAddKasaSuccessTimer(): void {
    if (this.addKasaSuccessTimeout !== null) {
      clearTimeout(this.addKasaSuccessTimeout);
      this.addKasaSuccessTimeout = null;
    }
  }

  refreshAllDevices(): void {
    let failCount = 0;

    this.devices.forEach(device => {
      this.smartDeviceService.refreshDeviceState(device.id).subscribe({
        next: (updatedDevice) => {
          this.updateDeviceInList(updatedDevice);
        },
        error: (error: Error) => {
          failCount++;
          if (failCount === 1) {
            // Zeige nur beim ersten Fehler eine Meldung
            this.errorMessage = `Fehler beim Aktualisieren von ${device.deviceName}: ${error.message}`;
          }
        }
      });
    });
  }

  toggleDevice(device: SmartDevice): void {
    if (!device.isOnline || this.isDeviceToggling(device.id)) {
      return;
    }

    this.togglingDevices.add(device.id);
    const action = device.isPoweredOn
      ? this.smartDeviceService.turnOff(device.id)
      : this.smartDeviceService.turnOn(device.id);

    action.subscribe({
      next: () => {
        device.isPoweredOn = !device.isPoweredOn;
        this.togglingDevices.delete(device.id);
      },
      error: (error: Error) => {
        this.errorMessage = `Fehler beim Schalten von ${device.deviceName}: ${error.message}`;
        this.togglingDevices.delete(device.id);
      }
    });
  }

  isDeviceToggling(deviceId: number): boolean {
    return this.togglingDevices.has(deviceId);
  }

  /** Flache, nach Typ-Reihenfolge sortierte Liste - für die Kompakt-Ansicht ohne Kategorien. */
  get orderedDevices(): SmartDevice[] {
    return this.groupedDevices.flatMap(group => group.devices);
  }

  get groupedDevices(): Array<{ type: SmartDevice['deviceType']; label: string; devices: SmartDevice[] }> {
    return this.typeOrder
      .map(type => ({
        type,
        label: this.getTypeLabel(type),
        devices: this.devices.filter(device => device.deviceType === type)
      }))
      .filter(group => group.devices.length > 0);
  }

  getTypeLabel(type: SmartDevice['deviceType']): string {
    switch (type) {
      case 'KASA':
        return 'Kasa';
      case 'TAPO':
        return 'Tapo';
      case 'MEROSS':
        return 'Meross';
      default:
        return type;
    }
  }

  getStatusText(device: SmartDevice): string {
    if (!device.isOnline) {
      return 'Offline';
    }
    return device.isPoweredOn ? 'Online - An' : 'Online - Aus';
  }

  getStatusClass(device: SmartDevice): string {
    if (!device.isOnline) {
      return 'device-card__status--offline';
    }
    return device.isPoweredOn ? 'device-card__status--on' : 'device-card__status--standby';
  }

  trackByDeviceId(_: number, device: SmartDevice): number {
    return device.id;
  }

  dismissError(): void {
    this.errorMessage = null;
  }

  private updateDeviceInList(updatedDevice: SmartDevice): void {
    const index = this.devices.findIndex(d => d.id === updatedDevice.id);
    if (index !== -1) {
      this.devices[index] = updatedDevice;
    }
  }

  private refreshAllDevicesInBackground(): void {
    this.devices.forEach(device => {
      this.smartDeviceService.refreshDeviceState(device.id).subscribe({
        next: (updatedDevice) => {
          this.updateDeviceInList(updatedDevice);
        },
        error: () => {
          // Stille Fehler - kein Error-Banner für Hintergrund-Updates
        }
      });
    });
  }
}
