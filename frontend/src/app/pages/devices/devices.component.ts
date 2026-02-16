import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SmartDevice } from '../../models/smart-device.model';

/**
 * User-facing smart device overview page.
 */
@Component({
  selector: 'app-devices',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './devices.component.html',
  styleUrl: './devices.component.scss'
})
export class DevicesComponent implements OnInit {
  private readonly smartDeviceService = inject(SmartDeviceService);
  private readonly typeOrder: ReadonlyArray<SmartDevice['deviceType']> = ['KASA', 'TAPO', 'MEROSS'];

  devices: SmartDevice[] = [];
  isLoading = true;
  errorMessage: string | null = null;
  togglingDevices = new Set<number>();

  ngOnInit(): void {
    this.loadDevices();
  }

  private updateDeviceInList(updatedDevice: SmartDevice): void {
    const index = this.devices.findIndex(d => d.id === updatedDevice.id);
    if (index !== -1) {
      this.devices[index] = updatedDevice;
    }
  }

  loadDevices(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.smartDeviceService.getAllDevices().subscribe({
      next: (devices) => {
        console.log('=== Loaded devices from API ===');
        console.log('Raw response:', JSON.stringify(devices, null, 2));
        devices.forEach((device, index) => {
          console.log(`Device ${index + 1}:`, {
            name: device.deviceName,
            isOnline: device.isOnline,
            isPoweredOn: device.isPoweredOn,
            type: device.deviceType
          });
        });
        this.devices = devices;
        this.isLoading = false;

        // Automatisch den aktuellen Status aller Geräte im Hintergrund aktualisieren
        setTimeout(() => {
          this.refreshAllDevicesInBackground();
        }, 500);
      },
      error: (error: Error) => {
        console.error('Error loading devices:', error);
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  private refreshAllDevicesInBackground(): void {
    console.log('Refreshing device states in background...');
    this.devices.forEach(device => {
      this.smartDeviceService.refreshDeviceState(device.id).subscribe({
        next: (updatedDevice) => {
          console.log('Updated device:', updatedDevice.deviceName, 'isOnline:', updatedDevice.isOnline, 'isPoweredOn:', updatedDevice.isPoweredOn);
          this.updateDeviceInList(updatedDevice);
        },
        error: (error: Error) => {
          console.warn(`Failed to refresh device ${device.deviceName}:`, error.message);
          // Stille Fehler - kein Error-Banner für Hintergrund-Updates
        }
      });
    });
  }

  refreshAllDevices(): void {
    console.log('Manually refreshing all devices...');
    let successCount = 0;
    let failCount = 0;
    const totalDevices = this.devices.length;

    this.devices.forEach(device => {
      this.smartDeviceService.refreshDeviceState(device.id).subscribe({
        next: (updatedDevice) => {
          console.log('Refreshed device:', updatedDevice.deviceName, 'isOnline:', updatedDevice.isOnline, 'isPoweredOn:', updatedDevice.isPoweredOn);
          this.updateDeviceInList(updatedDevice);
          successCount++;
        },
        error: (error: Error) => {
          console.error('Error refreshing device:', error);
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
        console.error('Error toggling device:', error);
        this.errorMessage = `Fehler beim Schalten von ${device.deviceName}: ${error.message}`;
        this.togglingDevices.delete(device.id);
      }
    });
  }

  isDeviceToggling(deviceId: number): boolean {
    return this.togglingDevices.has(deviceId);
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
}
