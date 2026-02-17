import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, catchError, forkJoin, of } from 'rxjs';
import { TasmotaPollingService } from '../../services/tasmota-polling.service';
import { TasmotaLiveService } from '../../services/tasmota-live.service';
import { TasmotaPollingStatus } from '../../models/tasmota-polling.model';
import { TasmotaLiveReading } from '../../models/tasmota-live.model';
import { KasaService } from '../../services/kasa.service';
import { KasaDiscoveryDevice, KasaStatus } from '../../models/kasa.model';
import { TapoService } from '../../services/tapo.service';
import { TapoDeviceInfo, TapoDiscoveryDevice, TapoEnergyUsage } from '../../models/tapo.model';
import { MerossService } from '../../services/meross.service';
import { MerossPlugResponse } from '../../models/meross.model';
import { UtilityPricesComponent } from '../utility-prices/utility-prices.component';

/**
 * Admin page for controlling the Tasmota polling service.
 */
@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, UtilityPricesComponent],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss'
})
export class AdminComponent implements OnInit, OnDestroy {
  private readonly pollingService = inject(TasmotaPollingService);
  private readonly liveService = inject(TasmotaLiveService);
  private readonly kasaService = inject(KasaService);
  private readonly tapoService = inject(TapoService);
  private readonly merossService = inject(MerossService);
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
  tapoInfoById: Partial<Record<string, TapoDeviceInfo>> = {};
  tapoEnergyById: Partial<Record<string, TapoEnergyUsage>> = {};
  selectedTapoDeviceId = '';
  isDiscoveringTapo = false;
  isLoadingTapoDetails = false;
  isTapoActionRunning = false;
  tapoErrorMessage: string | null = null;
  tapoSuccessMessage: string | null = null;
  merossDevices: MerossPlugResponse[] = [];
  merossStatus: MerossPlugResponse | null = null;
  selectedMerossDeviceId = '';
  isDiscoveringMeross = false;
  isLoadingMerossStatus = false;
  isMerossActionRunning = false;
  merossErrorMessage: string | null = null;
  merossSuccessMessage: string | null = null;
  activeTab: 'airrohr-config' | 'stromverbrauch' | 'smart-plugs' | 'versorgerpreise' = 'airrohr-config';

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
    this.isDiscoveringTapo = true;
    this.kasaErrorMessage = null;
    this.kasaSuccessMessage = null;
    this.tapoErrorMessage = null;
    this.tapoSuccessMessage = null;

    forkJoin({
      kasa: this.kasaService.discover().pipe(
        catchError((error: Error) => {
          console.error('Error discovering Kasa devices:', error);
          this.kasaErrorMessage = error.message;
          return of([] as KasaDiscoveryDevice[]);
        })
      ),
      tapo: this.tapoService.discover().pipe(
        catchError((error: Error) => {
          console.error('Error discovering Tapo devices:', error);
          this.tapoErrorMessage = error.message;
          return of([] as TapoDiscoveryDevice[]);
        })
      )
    }).subscribe({
      next: ({ kasa, tapo }) => {
        this.kasaDevices = kasa;
        if (kasa.length > 0) {
          this.selectedKasaIp = kasa[0].ip;
          this.loadKasaStatus();
          this.kasaSuccessMessage = `${kasa.length} Kasa-Geraet(e) gefunden.`;
        } else if (!this.kasaErrorMessage) {
          this.kasaStatus = null;
          this.kasaSuccessMessage = 'Keine Kasa-Geraete gefunden.';
        }

        this.tapoDevices = tapo;
        if (tapo.length > 0) {
          this.selectedTapoDeviceId = tapo[0].deviceId;
          this.tapoSuccessMessage = `${tapo.length} Tapo-Geraet(e) gefunden.`;
        } else if (!this.tapoErrorMessage) {
          this.tapoSuccessMessage = 'Keine Tapo-Geraete gefunden.';
        }

        this.isDiscoveringKasa = false;
        this.isDiscoveringTapo = false;
      },
      error: () => {
        this.isDiscoveringKasa = false;
        this.isDiscoveringTapo = false;
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
        if (devices.length > 0) {
          this.selectedTapoDeviceId = devices[0].deviceId;
        }
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

  setSelectedTapoDeviceId(deviceId: string): void {
    this.selectedTapoDeviceId = deviceId;
    // Don't automatically load details - user can click "Status laden" if needed
  }

  loadSingleTapoDetails(deviceId: string): void {
    if (!deviceId.trim()) {
      return;
    }

    this.isTapoActionRunning = true;
    this.tapoErrorMessage = null;

    forkJoin({
      info: this.tapoService.getDeviceInfo(deviceId).pipe(
        catchError((error: Error) => {
          console.error(`Error loading Tapo details for ${deviceId}:`, error);
          // Check if it's an unsupported device error
          if (error.message.includes('does not support this operation') ||
              error.message.includes('Module not support')) {
            this.tapoErrorMessage = 'Dieses Geraet (Kamera/Hub) unterstuetzt die Steuerung nicht.';
          } else {
            this.tapoErrorMessage = error.message;
          }
          return of(null as TapoDeviceInfo | null);
        })
      ),
      energy: this.tapoService.getEnergyUsage(deviceId).pipe(
        catchError(() => of(null as TapoEnergyUsage | null))
      )
    }).subscribe({
      next: ({ info, energy }) => {
        if (info) {
          this.tapoInfoById[deviceId] = info;
        }
        if (energy) {
          this.tapoEnergyById[deviceId] = energy;
        }
        this.isTapoActionRunning = false;
      },
      error: () => {
        this.isTapoActionRunning = false;
      }
    });
  }

  turnTapoOn(): void {
    this.runTapoAction('on');
  }

  turnTapoOff(): void {
    this.runTapoAction('off');
  }

  private runTapoAction(action: 'on' | 'off'): void {
    if (!this.selectedTapoDeviceId.trim()) {
      this.tapoErrorMessage = 'Bitte zuerst ein Geraet auswaehlen.';
      return;
    }

    const selectedDevice = this.getSelectedTapoDevice();
    if (!this.isTapoPowerControlSupported(selectedDevice)) {
      this.tapoErrorMessage = 'Dieses Geraet (z. B. Kamera/Hub/Sensor) kann nicht ein- oder ausgeschaltet werden.';
      return;
    }

    this.isTapoActionRunning = true;
    this.tapoErrorMessage = null;
    this.tapoSuccessMessage = null;

    const request = action === 'on'
      ? this.tapoService.turnOn(this.selectedTapoDeviceId.trim())
      : this.tapoService.turnOff(this.selectedTapoDeviceId.trim());

    request.subscribe({
      next: () => {
        this.tapoSuccessMessage = action === 'on' ? 'Geraet eingeschaltet.' : 'Geraet ausgeschaltet.';
        this.isTapoActionRunning = false;
        this.loadSingleTapoDetails(this.selectedTapoDeviceId.trim());
      },
      error: (error: Error) => {
        console.error(`Error switching Tapo ${action}:`, error);
        this.tapoErrorMessage = error.message;
        this.isTapoActionRunning = false;
      }
    });
  }

  getSelectedTapoDevice(): TapoDiscoveryDevice | null {
    return this.tapoDevices.find(device => device.deviceId === this.selectedTapoDeviceId) ?? null;
  }

  isTapoPowerControlSupported(device: TapoDiscoveryDevice | null): boolean {
    if (!device) {
      return false;
    }

    const type = (device.deviceType ?? '').toUpperCase();
    return !type.includes('CAMERA') && !type.includes('HUB') && !type.includes('SENSOR');
  }

  discoverMeross(): void {
    this.isDiscoveringMeross = true;
    this.merossErrorMessage = null;
    this.merossSuccessMessage = null;

    this.merossService.discoverPlugs().subscribe({
      next: (devices) => {
        this.merossDevices = devices;
        if (devices.length > 0) {
          this.selectedMerossDeviceId = devices[0].deviceId;
          this.loadMerossStatus();
          this.merossSuccessMessage = `${devices.length} Meross-Steckdose(n) gefunden.`;
        } else {
          this.selectedMerossDeviceId = '';
          this.merossStatus = null;
          this.merossSuccessMessage = 'Keine Meross-Steckdosen gefunden.';
        }
        this.isDiscoveringMeross = false;
      },
      error: (error: Error) => {
        console.error('Error discovering Meross plugs:', error);
        this.merossErrorMessage = error.message;
        this.isDiscoveringMeross = false;
      }
    });
  }

  loadMerossStatus(): void {
    if (!this.selectedMerossDeviceId.trim()) {
      this.merossErrorMessage = 'Bitte zuerst ein Geraet auswaehlen.';
      return;
    }

    this.isLoadingMerossStatus = true;
    this.merossErrorMessage = null;
    this.merossSuccessMessage = null;

    this.merossService.getStatus(this.selectedMerossDeviceId.trim()).subscribe({
      next: (status) => {
        this.merossStatus = status;
        this.isLoadingMerossStatus = false;
      },
      error: (error: Error) => {
        console.error('Error loading Meross status:', error);
        this.merossErrorMessage = error.message;
        this.isLoadingMerossStatus = false;
      }
    });
  }

  setSelectedMerossDeviceId(deviceId: string): void {
    this.selectedMerossDeviceId = deviceId;
    this.loadMerossStatus();
  }

  turnMerossOn(): void {
    this.runMerossAction('on');
  }

  turnMerossOff(): void {
    this.runMerossAction('off');
  }

  setActiveTab(tab: 'airrohr-config' | 'stromverbrauch' | 'smart-plugs' | 'versorgerpreise'): void {
    this.activeTab = tab;
  }

  private runMerossAction(action: 'on' | 'off'): void {
    if (!this.selectedMerossDeviceId.trim()) {
      this.merossErrorMessage = 'Bitte zuerst ein Geraet auswaehlen.';
      return;
    }

    this.isMerossActionRunning = true;
    this.merossErrorMessage = null;
    this.merossSuccessMessage = null;

    const request = action === 'on'
      ? this.merossService.turnOn(this.selectedMerossDeviceId.trim())
      : this.merossService.turnOff(this.selectedMerossDeviceId.trim());

    request.subscribe({
      next: () => {
        this.merossSuccessMessage = action === 'on'
          ? 'Meross-Steckdose eingeschaltet.'
          : 'Meross-Steckdose ausgeschaltet.';
        this.isMerossActionRunning = false;
        this.loadMerossStatus();
      },
      error: (error: Error) => {
        console.error(`Error switching Meross ${action}:`, error);
        this.merossErrorMessage = error.message;
        this.isMerossActionRunning = false;
      }
    });
  }

}
