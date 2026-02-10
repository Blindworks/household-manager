import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TasmotaPollingService } from '../../services/tasmota-polling.service';
import { TasmotaPollingStatus, TasmotaPollingUpdateRequest } from '../../models/tasmota-polling.model';

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
export class AdminComponent implements OnInit {
  private readonly pollingService = inject(TasmotaPollingService);

  status: TasmotaPollingStatus | null = null;
  isLoading = true;
  isSaving = false;
  isTriggering = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  form: TasmotaPollingUpdateRequest = {
    enabled: true,
    intervalMs: 60000,
    url: ''
  };

  ngOnInit(): void {
    this.loadStatus();
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

  formatDate(value: string | null): string {
    if (!value) {
      return '—';
    }
    return new Date(value).toLocaleString('de-DE');
  }
}
