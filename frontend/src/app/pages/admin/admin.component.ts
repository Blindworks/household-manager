import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { TasmotaPollingService } from '../../services/tasmota-polling.service';
import { TasmotaLiveService } from '../../services/tasmota-live.service';
import { TasmotaPollingStatus } from '../../models/tasmota-polling.model';
import { TasmotaLiveReading } from '../../models/tasmota-live.model';
import { KasaService } from '../../services/kasa.service';
import { KasaDiscoveryDevice, KasaStatus } from '../../models/kasa.model';
import { TapoService } from '../../services/tapo.service';
import { TapoDiscoveryDevice } from '../../models/tapo.model';

/**
 * Admin page for controlling the Tasmota polling service.
 */
@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss'
})
export class AdminComponent implements OnInit, OnDestroy {
  private readonly pollingService = inject(TasmotaPollingService);
  private readonly liveService = inject(TasmotaLiveService);
  private readonly kasaService = inject(KasaService);
  private readonly tapoService = inject(TapoService);
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
  kasaDevices: KasaDiscoveryDevice[] = [];
  selectedKasaIp = '';
  kasaStatus: KasaStatus | null = null;
  isDiscoveringKasa = false;
  isLoadingKasaStatus = false;
  isKasaActionRunning = false;
  kasaErrorMessage: string | null = null;
  kasaSuccessMessage: string | null = null;
  tapoDevices: TapoDiscoveryDevice[] = [];
  isDiscoveringTapo = false;
  tapoErrorMessage: string | null = null;
  tapoSuccessMessage: string | null = null;

  ngOnInit(): void {
    this.loadStatus();
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

  discoverKasa(): void {
    this.isDiscoveringKasa = true;
    this.kasaErrorMessage = null;
    this.kasaSuccessMessage = null;

    this.kasaService.discover().subscribe({
      next: (devices) => {
        this.kasaDevices = devices;
        if (devices.length > 0) {
          this.selectedKasaIp = devices[0].ip;
          this.loadKasaStatus();
          this.kasaSuccessMessage = `${devices.length} Kasa-Geraet(e) gefunden.`;
        } else {
          this.kasaStatus = null;
          this.kasaSuccessMessage = 'Keine Kasa-Geraete gefunden.';
        }
        this.isDiscoveringKasa = false;
      },
      error: (error: Error) => {
        console.error('Error discovering Kasa devices:', error);
        this.kasaErrorMessage = error.message;
        this.isDiscoveringKasa = false;
      }
    });
  }

  loadKasaStatus(): void {
    if (!this.selectedKasaIp.trim()) {
      this.kasaErrorMessage = 'Bitte zuerst eine IP auswaehlen oder eingeben.';
      return;
    }

    this.isLoadingKasaStatus = true;
    this.kasaErrorMessage = null;
    this.kasaSuccessMessage = null;

    this.kasaService.getStatus(this.selectedKasaIp.trim()).subscribe({
      next: (status) => {
        this.kasaStatus = status;
        this.isLoadingKasaStatus = false;
      },
      error: (error: Error) => {
        console.error('Error loading Kasa status:', error);
        this.kasaErrorMessage = error.message;
        this.isLoadingKasaStatus = false;
      }
    });
  }

  setSelectedKasaIp(ip: string): void {
    this.selectedKasaIp = ip;
    this.loadKasaStatus();
  }

  turnKasaOn(): void {
    this.runKasaAction('on');
  }

  turnKasaOff(): void {
    this.runKasaAction('off');
  }

  private runKasaAction(action: 'on' | 'off'): void {
    if (!this.selectedKasaIp.trim()) {
      this.kasaErrorMessage = 'Bitte zuerst eine IP auswaehlen oder eingeben.';
      return;
    }

    this.isKasaActionRunning = true;
    this.kasaErrorMessage = null;
    this.kasaSuccessMessage = null;

    const request = action === 'on'
      ? this.kasaService.turnOn(this.selectedKasaIp.trim())
      : this.kasaService.turnOff(this.selectedKasaIp.trim());

    request.subscribe({
      next: () => {
        this.kasaSuccessMessage = action === 'on' ? 'Steckdose eingeschaltet.' : 'Steckdose ausgeschaltet.';
        this.isKasaActionRunning = false;
        this.loadKasaStatus();
      },
      error: (error: Error) => {
        console.error(`Error switching Kasa ${action}:`, error);
        this.kasaErrorMessage = error.message;
        this.isKasaActionRunning = false;
      }
    });
  }

  discoverTapo(): void {
    this.isDiscoveringTapo = true;
    this.tapoErrorMessage = null;
    this.tapoSuccessMessage = null;

    this.tapoService.discover().subscribe({
      next: (devices) => {
        this.tapoDevices = devices;
        this.tapoSuccessMessage = devices.length > 0
          ? `${devices.length} Tapo-Geraet(e) gefunden.`
          : 'Keine Tapo-Geraete gefunden.';
        this.isDiscoveringTapo = false;
      },
      error: (error: Error) => {
        console.error('Error discovering Tapo devices:', error);
        this.tapoErrorMessage = error.message;
        this.isDiscoveringTapo = false;
      }
    });
  }
}
