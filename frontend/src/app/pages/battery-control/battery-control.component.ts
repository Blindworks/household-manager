import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { AnkerSolixAutoControlSettings, AnkerSolixAutoControlStatus } from '../../models/ankersolix.model';

/**
 * Battery auto-control page for configuring and monitoring
 * the automatic output power regulation of the Anker Solix solarbank.
 */
@Component({
  selector: 'app-battery-control',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './battery-control.component.html',
  styleUrl: './battery-control.component.scss'
})
export class BatteryControlComponent implements OnInit, OnDestroy {
  private readonly ankerSolixService = inject(AnkerSolixService);

  status: AnkerSolixAutoControlStatus | null = null;
  settings: AnkerSolixAutoControlSettings | null = null;

  /** Interval in seconds for the UI (converted from/to ms for the API) */
  intervalSeconds = 30;

  isSaving = false;
  saveSuccess = false;
  saveError = '';

  private statusInterval: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.loadStatus();
    this.loadSettings();
    this.statusInterval = setInterval(() => this.loadStatus(), 30000);
  }

  ngOnDestroy(): void {
    if (this.statusInterval) {
      clearInterval(this.statusInterval);
    }
  }

  saveSettings(): void {
    if (!this.settings) {
      return;
    }

    this.isSaving = true;
    this.saveSuccess = false;
    this.saveError = '';

    const payload: AnkerSolixAutoControlSettings = {
      enabled: this.settings.enabled,
      thresholdW: this.settings.thresholdW,
      intervalMs: this.intervalSeconds * 1000,
      forceDischargeEnabled: this.settings.forceDischargeEnabled,
      forceDischargeMinBatteryPercent: this.settings.forceDischargeMinBatteryPercent
    };

    this.ankerSolixService.updateAutoControlSettings(payload).subscribe({
      next: (updated) => {
        this.settings = updated;
        this.intervalSeconds = Math.round(updated.intervalMs / 1000);
        this.isSaving = false;
        this.saveSuccess = true;
        this.loadStatus();
        setTimeout(() => { this.saveSuccess = false; }, 3000);
      },
      error: (err) => {
        this.isSaving = false;
        this.saveError = 'Fehler beim Speichern der Einstellungen';
        console.error('Fehler beim Speichern der Auto-Control-Einstellungen:', err);
      }
    });
  }

  toggleEnabled(): void {
    if (!this.settings) {
      return;
    }
    this.settings = { ...this.settings, enabled: !this.settings.enabled };
    this.saveSettings();
  }

  toggleForceDischarge(): void {
    if (!this.settings) {
      return;
    }
    this.settings = { ...this.settings, forceDischargeEnabled: !this.settings.forceDischargeEnabled };
    this.saveSettings();
  }

  private loadStatus(): void {
    this.ankerSolixService.getAutoControlStatus().subscribe({
      next: (status) => { this.status = status; },
      error: () => { this.status = null; }
    });
  }

  private loadSettings(): void {
    this.ankerSolixService.getAutoControlSettings().subscribe({
      next: (settings) => {
        this.settings = settings;
        this.intervalSeconds = Math.round(settings.intervalMs / 1000);
      },
      error: (err) => console.error('Fehler beim Laden der Auto-Control-Einstellungen:', err)
    });
  }
}
