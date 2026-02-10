import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { TasmotaPollingService } from '../../services/tasmota-polling.service';
import { TasmotaLiveService } from '../../services/tasmota-live.service';
import { TasmotaPollingStatus, TasmotaPollingUpdateRequest } from '../../models/tasmota-polling.model';
import { TasmotaLiveReading } from '../../models/tasmota-live.model';

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
  private liveSubscription?: Subscription;
  private statusSubscription?: Subscription;

  status: TasmotaPollingStatus | null = null;
  isLoading = true;
  isSaving = false;
  isTriggering = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  liveReading: TasmotaLiveReading | null = null;
  liveError: string | null = null;
  liveStatus: 'disconnected' | 'connecting' | 'connected' | 'error' = 'disconnected';
  lastLiveUpdate: Date | null = null;
  isReconnecting = false;

  form: TasmotaPollingUpdateRequest = {
    enabled: true,
    intervalMs: 60000,
    url: ''
  };

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
        this.form = {
          enabled: status.enabled,
          intervalMs: status.intervalMs,
          url: status.url
        };
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Error loading polling status:', error);
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  save(): void {
    this.isSaving = true;
    this.errorMessage = null;
    this.successMessage = null;

    this.pollingService.updateConfig(this.form).subscribe({
      next: (status) => {
        this.status = status;
        this.successMessage = 'Einstellungen gespeichert.';
        this.isSaving = false;
      },
      error: (error: Error) => {
        console.error('Error saving polling config:', error);
        this.errorMessage = error.message;
        this.isSaving = false;
      }
    });
  }

  trigger(): void {
    this.isTriggering = true;
    this.errorMessage = null;
    this.successMessage = null;

    this.pollingService.triggerOnce().subscribe({
      next: () => {
        this.successMessage = 'Polling ausgelöst.';
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
        this.liveError = 'Live-Stream nicht verfügbar.';
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
      return '—';
    }
    return new Date(value).toLocaleString('de-DE');
  }

  formatPower(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
      return '—';
    }
    return `${value.toLocaleString('de-DE', { maximumFractionDigits: 1 })} W`;
  }

  formatStatus(): string {
    switch (this.liveStatus) {
      case 'connected':
        return 'Verbunden';
      case 'connecting':
        return 'Verbinde…';
      case 'error':
        return 'Fehler';
      default:
        return 'Getrennt';
    }
  }
}
