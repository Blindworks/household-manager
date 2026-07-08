import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
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
  imports: [CommonModule],
  templateUrl: './smart-device-list.component.html',
  styleUrl: './smart-device-list.component.scss'
})
export class SmartDeviceListComponent implements OnInit {
  private static readonly VIEW_MODE_STORAGE_KEY = 'smartDeviceViewMode';

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

  ngOnInit(): void {
    this.viewMode = this.readStoredViewMode();
    this.loadDevices();
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
