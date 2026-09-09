import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ModeQuickAccessService } from '../../services/mode-quick-access.service';
import { ModeService } from '../../services/mode.service';
import { ModeQuickAccess, ModeQuickAccessRequest } from '../../models/mode-quick-access.model';
import { ModeEntity } from '../../models/mode.model';

/** Zustand des Anlege-/Bearbeiten-Formulars. */
interface WindowFormState {
  /** null = Anlegen, sonst die Id des bearbeiteten Fensters. */
  id: number | null;
  entityId: string;
  fromTime: string;
  toTime: string;
  active: boolean;
}

function emptyForm(): WindowFormState {
  return { id: null, entityId: '', fromTime: '20:00', toTime: '06:00', active: true };
}

/**
 * Admin-Seite „Modus-Schnellzugriff": pflegt die Zeitfenster, in denen ein Haus-Modus im
 * Tablet-Dashboard direkt als Knopf steht. Muster und Interaktionsform an der Admin-Seite
 * „Netzwerk-Geräte" ausgerichtet.
 */
@Component({
  selector: 'app-admin-mode-quick-access',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-mode-quick-access.component.html',
  styleUrl: './admin-mode-quick-access.component.scss'
})
export class AdminModeQuickAccessComponent implements OnInit {
  private readonly api = inject(ModeQuickAccessService);
  private readonly modeApi = inject(ModeService);

  readonly windows = signal<ModeQuickAccess[]>([]);
  /** Auswahl des Dropdowns; leer, wenn die Modi nicht geladen werden konnten. */
  readonly modes = signal<ModeEntity[]>([]);
  /** Nur der erste Abruf blendet die Tabelle aus; spaetere lassen sie stehen. */
  readonly loading = signal(true);
  /** Bei fehlgeschlagenem Laden bleibt die Tabelle verborgen — eine leere Liste loege. */
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  form: WindowFormState = emptyForm();

  ngOnInit(): void {
    this.load();
    this.loadModes();
  }

  /** Laedt die Fensterliste neu. `afterLoad` laeuft auch im Fehlerfall. */
  load(afterLoad?: () => void): void {
    this.api.list().subscribe({
      next: windows => {
        this.windows.set(windows);
        this.loadFailed.set(false);
        this.loading.set(false);
        afterLoad?.();
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set(this.messageFrom(error));
        afterLoad?.();
      }
    });
  }

  /**
   * Laedt die Haus-Modi fuer das Dropdown. Ein Fehlschlag blockiert die Pflege nicht:
   * die Liste bleibt sichtbar, nur die Auswahl ist leer.
   */
  private loadModes(): void {
    this.modeApi.getModes().subscribe({
      next: modes => this.modes.set(modes),
      error: () => this.modes.set([])
    });
  }

  get editing(): boolean {
    return this.form.id !== null;
  }

  /** Anzeigename eines Fensters; ohne zugehoerigen Modus die rohe Entity-ID. */
  label(window: ModeQuickAccess): string {
    return window.displayName ?? window.entityId;
  }

  /** "20:00:00" -> "20:00"; ein `<input type="time">` erwartet die kurze Form. */
  shortTime(time: string): string {
    return time.slice(0, 5);
  }

  startEdit(window: ModeQuickAccess): void {
    this.errorMessage.set(null);
    this.form = {
      id: window.id,
      entityId: window.entityId,
      fromTime: this.shortTime(window.fromTime),
      toTime: this.shortTime(window.toTime),
      active: window.active
    };
  }

  resetForm(): void {
    this.form = emptyForm();
    this.errorMessage.set(null);
  }

  save(): void {
    if (!this.form.entityId) {
      this.errorMessage.set('Es ist kein Modus ausgewählt.');
      return;
    }
    if (!this.form.fromTime || !this.form.toTime) {
      this.errorMessage.set('Beginn und Ende müssen gesetzt sein.');
      return;
    }
    if (this.form.fromTime === this.form.toTime) {
      this.errorMessage.set('Beginn und Ende dürfen nicht gleich sein — das Fenster wäre leer.');
      return;
    }
    const request: ModeQuickAccessRequest = {
      entityId: this.form.entityId,
      fromTime: this.form.fromTime,
      toTime: this.form.toTime,
      active: this.form.active
    };
    const id = this.form.id;
    this.saving.set(true);
    this.errorMessage.set(null);
    const call = id === null ? this.api.create(request) : this.api.update(id, request);
    call.subscribe({
      next: () => {
        // Ein neues/geaendertes Fenster kann sofort quickAccess eines Modus umklappen
        // (liegt „jetzt" im neuen Fenster) — deshalb beide Listen neu laden.
        this.load(() => {
          this.saving.set(false);
          this.resetForm();
        });
        this.loadModes();
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(this.messageFrom(error));
      }
    });
  }

  setActive(window: ModeQuickAccess, active: boolean): void {
    this.errorMessage.set(null);
    this.api.update(window.id, this.requestFrom(window, active)).subscribe({
      next: () => {
        // Steht dasselbe Fenster gerade im Formular, muss der Schalter dort mitwandern.
        if (this.form.id === window.id) {
          this.form.active = active;
        }
        this.load();
        this.loadModes();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  remove(window: ModeQuickAccess): void {
    if (!confirm(`Zeitfenster für „${this.label(window)}“ endgültig löschen?`)) {
      return;
    }
    this.errorMessage.set(null);
    this.api.remove(window.id).subscribe({
      next: () => {
        // Stand das geloeschte Fenster im Formular, ist dessen Id jetzt tot.
        if (this.form.id === window.id) {
          this.resetForm();
        }
        this.load();
        this.loadModes();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  /**
   * Vollstaendiger Request aus einem bestehenden Fenster, mit ausgetauschtem `active`.
   * Der Server liest ein fehlendes `active` als „aktiv" — ein Teil-PUT wuerde ein
   * deaktiviertes Fenster also stillschweigend wieder aktivieren.
   */
  private requestFrom(window: ModeQuickAccess, active: boolean): ModeQuickAccessRequest {
    return {
      entityId: window.entityId,
      fromTime: window.fromTime,
      toTime: window.toTime,
      active
    };
  }

  private messageFrom(error: HttpErrorResponse): string {
    return error.error?.message ?? 'Fehler bei der Netzwerk-Kommunikation.';
  }
}
