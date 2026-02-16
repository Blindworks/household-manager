import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SmartDevice } from '../../models/smart-device.model';

/**
 * Devices page for managing smart home devices across all platforms.
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

  devices: SmartDevice[] = [];
  isLoading = true;
  isScanningKasa = false;
  isScanningTapo = false;
  isScanningMeross = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  ngOnInit(): void {
    this.loadDevices();
  }

  loadDevices(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.smartDeviceService.getAllDevices().subscribe({
      next: (devices) => {
        this.devices = devices;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Error loading devices:', error);
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  scanKasa(): void {
    this.isScanningKasa = true;
    this.errorMessage = null;
    this.successMessage = null;
    this.smartDeviceService.scanDevices('KASA').subscribe({
      next: (devices) => {
        this.successMessage = `${devices.length} Kasa-Geraete gescannt und gespeichert`;
        this.loadDevices();
        this.isScanningKasa = false;
      },
      error: (error: Error) => {
        console.error('Error scanning Kasa devices:', error);
        this.errorMessage = error.message;
        this.isScanningKasa = false;
      }
    });
  }

  scanTapo(): void {
    this.isScanningTapo = true;
    this.errorMessage = null;
    this.successMessage = null;
    this.smartDeviceService.scanDevices('TAPO').subscribe({
      next: (devices) => {
        this.successMessage = `${devices.length} Tapo-Geraete gescannt und gespeichert`;
        this.loadDevices();
        this.isScanningTapo = false;
      },
      error: (error: Error) => {
        console.error('Error scanning Tapo devices:', error);
        this.errorMessage = error.message;
        this.isScanningTapo = false;
      }
    });
  }

  scanMeross(): void {
    this.isScanningMeross = true;
    this.errorMessage = null;
    this.successMessage = null;
    this.smartDeviceService.scanDevices('MEROSS').subscribe({
      next: (devices) => {
        this.successMessage = `${devices.length} Meross-Geraete gescannt und gespeichert`;
        this.loadDevices();
        this.isScanningMeross = false;
      },
      error: (error: Error) => {
        console.error('Error scanning Meross devices:', error);
        this.errorMessage = error.message;
        this.isScanningMeross = false;
      }
    });
  }

  toggleDevice(device: SmartDevice): void {
    const action = device.isPoweredOn
      ? this.smartDeviceService.turnOff(device.id)
      : this.smartDeviceService.turnOn(device.id);

    action.subscribe({
      next: () => {
        device.isPoweredOn = !device.isPoweredOn;
        this.successMessage = `${device.deviceName} ${device.isPoweredOn ? 'eingeschaltet' : 'ausgeschaltet'}`;
      },
      error: (error: Error) => {
        console.error('Error toggling device:', error);
        this.errorMessage = error.message;
      }
    });
  }

  refreshDevice(device: SmartDevice): void {
    this.smartDeviceService.refreshDeviceState(device.id).subscribe({
      next: (updated) => {
        const index = this.devices.findIndex(d => d.id === device.id);
        if (index !== -1) {
          this.devices[index] = updated;
        }
        this.successMessage = `${device.deviceName} aktualisiert`;
      },
      error: (error: Error) => {
        console.error('Error refreshing device:', error);
        this.errorMessage = error.message;
      }
    });
  }

  refreshAll(): void {
    this.loadDevices();
    this.successMessage = 'Alle Geraete aktualisiert';
  }

  deleteDevice(device: SmartDevice): void {
    if (!confirm(`Geraet "${device.deviceName}" wirklich loeschen?`)) {
      return;
    }

    this.smartDeviceService.deleteDevice(device.id).subscribe({
      next: () => {
        this.devices = this.devices.filter(d => d.id !== device.id);
        this.successMessage = `${device.deviceName} geloescht`;
      },
      error: (error: Error) => {
        console.error('Error deleting device:', error);
        this.errorMessage = error.message;
      }
    });
  }

  get kasaDevices(): SmartDevice[] {
    return this.devices.filter(d => d.deviceType === 'KASA');
  }

  get tapoDevices(): SmartDevice[] {
    return this.devices.filter(d => d.deviceType === 'TAPO');
  }

  get merossDevices(): SmartDevice[] {
    return this.devices.filter(d => d.deviceType === 'MEROSS');
  }

  dismissError(): void {
    this.errorMessage = null;
  }

  dismissSuccess(): void {
    this.successMessage = null;
  }
}
