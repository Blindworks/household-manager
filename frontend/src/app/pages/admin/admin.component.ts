import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { TasmotaPollingService } from '../../services/tasmota-polling.service';
import { TasmotaLiveService } from '../../services/tasmota-live.service';
import { TasmotaPollingStatus } from '../../models/tasmota-polling.model';
import { TasmotaLiveReading } from '../../models/tasmota-live.model';
import { WeatherPollingService } from '../../services/weather-polling.service';
import { WeatherPollingStatus } from '../../models/weather-polling.model';
import { UtilityPricesComponent } from '../utility-prices/utility-prices.component';
import { SmartDeviceListComponent } from '../../components/smart-device-list/smart-device-list.component';

/**
 * Admin page for controlling the Tasmota polling service.
 */
@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, UtilityPricesComponent, SmartDeviceListComponent],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss'
})
export class AdminComponent implements OnInit, OnDestroy {
  private readonly pollingService = inject(TasmotaPollingService);
  private readonly liveService = inject(TasmotaLiveService);
  private readonly weatherPollingService = inject(WeatherPollingService);
  private liveSubscription?: Subscription;
  private statusSubscription?: Subscription;

  status: TasmotaPollingStatus | null = null;
  isLoading = true;
  isTriggering = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  liveReading: TasmotaLiveReading | null = null;
  liveError: string | null = null;
  liveStatus: 'disconnected' | 'connecting' | 'connected' | 'error' = 'disconnected';
  lastLiveUpdate: Date | null = null;
  isReconnecting = false;
  weatherStatus: WeatherPollingStatus | null = null;
  isWeatherLoading = true;
  isWeatherTriggering = false;
  weatherErrorMessage: string | null = null;
  weatherSuccessMessage: string | null = null;
  activeTab: 'airrohr-config' | 'stromverbrauch' | 'smart-plugs' | 'wetter' | 'versorgerpreise' = 'airrohr-config';

  ngOnInit(): void {
    this.loadStatus();
    this.loadWeatherStatus();
    this.startLiveStream();
  }

  ngOnDestroy(): void {
    this.liveSubscription?.unsubscribe();
    this.statusSubscription?.unsubscribe();
    this.liveService.disconnect();
  }

  loadStatus(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.pollingService.getStatus().subscribe({
      next: (status) => {
        this.status = status;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Error loading polling status:', error);
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  trigger(): void {
    this.isTriggering = true;
    this.errorMessage = null;
    this.successMessage = null;

    this.pollingService.triggerOnce().subscribe({
      next: () => {
        this.successMessage = 'Polling ausgeloest.';
        this.isTriggering = false;
        setTimeout(() => this.loadStatus(), 800);
      },
      error: (error: Error) => {
        console.error('Error triggering polling:', error);
        this.errorMessage = error.message;
        this.isTriggering = false;
      }
    });
  }

  private startLiveStream(): void {
    this.liveSubscription?.unsubscribe();
    this.statusSubscription?.unsubscribe();
    this.liveError = null;
    this.liveSubscription = this.liveService.getLiveStream().subscribe({
      next: (reading) => {
        this.liveReading = reading;
        this.lastLiveUpdate = new Date();
        this.liveError = null;
      },
      error: () => {
        this.liveError = 'Live-Stream nicht verfuegbar.';
      }
    });

    this.statusSubscription = this.liveService.getStatusStream().subscribe({
      next: (status) => {
        this.liveStatus = status;
      }
    });
  }

  reconnectLive(): void {
    this.isReconnecting = true;
    this.liveService.reconnect();
    setTimeout(() => {
      this.isReconnecting = false;
    }, 600);
  }

  formatDate(value: string | null): string {
    if (!value) {
      return '-';
    }
    return new Date(value).toLocaleString('de-DE');
  }

  formatPower(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
      return '-';
    }
    return `${value.toLocaleString('de-DE', { maximumFractionDigits: 1 })} W`;
  }

  formatStatus(): string {
    switch (this.liveStatus) {
      case 'connected':
        return 'Verbunden';
      case 'connecting':
        return 'Verbinde...';
      case 'error':
        return 'Fehler';
      default:
        return 'Getrennt';
    }
  }

  setActiveTab(tab: 'airrohr-config' | 'stromverbrauch' | 'smart-plugs' | 'wetter' | 'versorgerpreise'): void {
    this.activeTab = tab;
  }

  loadWeatherStatus(): void {
    this.isWeatherLoading = true;
    this.weatherErrorMessage = null;
    this.weatherPollingService.getStatus().subscribe({
      next: (status) => {
        this.weatherStatus = status;
        this.isWeatherLoading = false;
      },
      error: (error: Error) => {
        console.error('Error loading weather polling status:', error);
        this.weatherErrorMessage = error.message;
        this.isWeatherLoading = false;
      }
    });
  }

  triggerWeather(): void {
    this.isWeatherTriggering = true;
    this.weatherErrorMessage = null;
    this.weatherSuccessMessage = null;

    this.weatherPollingService.triggerOnce().subscribe({
      next: () => {
        this.weatherSuccessMessage = 'Wetterabruf ausgeloest.';
        this.isWeatherTriggering = false;
        setTimeout(() => this.loadWeatherStatus(), 800);
      },
      error: (error: Error) => {
        console.error('Error triggering weather polling:', error);
        this.weatherErrorMessage = error.message;
        this.isWeatherTriggering = false;
      }
    });
  }

}
