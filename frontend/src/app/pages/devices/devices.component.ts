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
