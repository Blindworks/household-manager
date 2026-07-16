import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { AlexaDevicePickerComponent } from '../../components/alexa-device-picker/alexa-device-picker.component';
import {
  WasteCollectionEvent, WasteCollectionPollingStatus, WasteCollectionSettings
} from '../../models/waste-collection.model';

/** Einstellungsseite fuer den Muellabfuhr-Kalender: Termine, Konfiguration, Abruf-Status. */
@Component({
  selector: 'app-waste-collection',
  standalone: true,
  imports: [CommonModule, FormsModule, AlexaDevicePickerComponent],
  templateUrl: './waste-collection.component.html',
  styleUrl: './waste-collection.component.scss'
})
export class WasteCollectionComponent implements OnInit {
  private readonly wasteService = inject(WasteCollectionService);

  /** Ausblick der Terminliste auf dieser Seite — unabhaengig vom Dashboard-Fenster. */
  private static readonly PREVIEW_DAYS = 60;

  settings: WasteCollectionSettings | null = null;
  status: WasteCollectionPollingStatus | null = null;
  events: WasteCollectionEvent[] = [];

  isSaving = false;
  saveSuccess = false;
  saveError = '';

  isTriggering = false;
  triggerError = '';

  ngOnInit(): void {
    this.loadSettings();
    this.loadStatus();
    this.loadEvents();
  }

  loadSettings(): void {
    this.wasteService.getSettings().subscribe({
      next: settings => this.settings = settings,
      error: err => console.error('Muellabfuhr-Einstellungen nicht ladbar:', err)
    });
  }

  loadStatus(): void {
    this.wasteService.getPollingStatus().subscribe({
      next: status => this.status = status,
      error: err => console.error('Abruf-Status nicht ladbar:', err)
    });
  }

  loadEvents(): void {
    this.wasteService.getUpcoming(WasteCollectionComponent.PREVIEW_DAYS).subscribe({
      next: events => this.events = events,
      error: err => console.error('Termine nicht ladbar:', err)
    });
  }

  saveSettings(): void {
    if (!this.settings) {
      return;
    }
    this.isSaving = true;
    this.saveSuccess = false;
    this.saveError = '';

    this.wasteService.updateSettings(this.settings).subscribe({
      next: updated => {
        this.settings = updated;
        this.isSaving = false;
        this.saveSuccess = true;
        setTimeout(() => { this.saveSuccess = false; }, 3000);
      },
      error: err => {
        this.isSaving = false;
        this.saveError = err.message || 'Fehler beim Speichern der Einstellungen';
      }
    });
  }

  /** Stoesst den Abruf an und laedt danach Status und Termine neu. */
  triggerPoll(): void {
    this.isTriggering = true;
    this.triggerError = '';

    this.wasteService.triggerPoll().subscribe({
      next: () => {
        this.isTriggering = false;
        this.loadStatus();
        this.loadEvents();
      },
      error: err => {
        this.isTriggering = false;
        this.triggerError = err.message || 'Abruf konnte nicht ausgeloest werden';
      }
    });
  }

  onSerialsChange(serials: string[]): void {
    if (this.settings) {
      this.settings.reminderAlexaSerials = serials;
    }
  }

  trackByEvent(_index: number, event: WasteCollectionEvent): string {
    return `${event.date}|${event.label}`;
  }
}
