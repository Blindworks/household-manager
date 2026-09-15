import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ModeQuickAccessService } from '../../services/mode-quick-access.service';
import { EntityStateService } from '../../services/entity-state.service';
import { ModeQuickAccess, ModeQuickAccessRequest } from '../../models/mode-quick-access.model';
import { EntityState, MANUAL_SOURCE, MODES_TILE_KEY, TileVisibility } from '../../models/entity-state.model';

/** Ein Ein/Aus-Helfer (Haus-Modus oder gewoehnlicher Helfer) mit seinem Leisten-Status. */
export interface HelperOption {
  entityId: string;
  displayName: string;
  /** True fuer Katalog-Modi (Marker `mode`): ohne Regel sichtbar, per NEVER ausblendbar. */
  isMode: boolean;
  /** True, wenn der Helfer aktuell in der Modus-Leiste steht. */
  inBar: boolean;
}

/**
 * Einzige Definition von "steht in der Modus-Leiste" auf Frontend-Seite — spiegelt
 * HouseModeQueryService.inBar: ALWAYS ja, NEVER nein, WHEN_ON solange an, AUTO nur Modi.
 */
export function helperOptionFrom(entity: EntityState): HelperOption {
  const isMode = entity.attributes?.['mode'] === true;
  const rule: TileVisibility = entity.tileVisibility?.[MODES_TILE_KEY] ?? 'AUTO';
  const inBar = rule === 'ALWAYS'
    || (rule === 'WHEN_ON' && entity.state === 'on')
    || (rule === 'AUTO' && isMode);
  return { entityId: entity.entityId, displayName: entity.displayName, isMode, inBar };
}

/** Zustand des Anlege-/Bearbeiten-Formulars. */
interface WindowFormState {
  /** null = Anlegen, sonst die Id des bearbeiteten Fensters. */
  id: number | null;
  entityId: string;
  /** True = kein Zeitfenster, der Helfer steht immer im Schnellzugriff; die Zeiten sind dann inaktiv. */
  always: boolean;
  fromTime: string;
  toTime: string;
  active: boolean;
}

function emptyForm(): WindowFormState {
  return { id: null, entityId: '', always: false, fromTime: '20:00', toTime: '06:00', active: true };
}

/**
 * Admin-Seite „Modus-Schnellzugriff": pflegt die Zeitfenster, in denen ein Helfer (Haus-Modus
 * oder gewoehnlicher INPUT_BOOLEAN-Helfer) im Tablet-Dashboard direkt als Knopf steht.
 * Muster und Interaktionsform an der Admin-Seite „Netzwerk-Geräte" ausgerichtet.
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
  private readonly entityApi = inject(EntityStateService);

  readonly windows = signal<ModeQuickAccess[]>([]);
  /** Alle Ein/Aus-Helfer mit Leisten-Status; leer, wenn sie nicht geladen werden konnten. */
  readonly helpers = signal<HelperOption[]>([]);
  /** Helfer, deren Leisten-Schalter gerade gespeichert wird (Doppelklick-Schutz). */
  readonly barPending = signal<Set<string>>(new Set());
  /** Nur der erste Abruf blendet die Tabelle aus; spaetere lassen sie stehen. */
  readonly loading = signal(true);
  /** Bei fehlgeschlagenem Laden bleibt die Tabelle verborgen — eine leere Liste loege. */
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  form: WindowFormState = emptyForm();

  ngOnInit(): void {
    this.load();
    this.loadHelpers();
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
   * Laedt alle Helfer vom Typ INPUT_BOOLEAN (Modi eingeschlossen) samt Leisten-Status. Ein
   * Fehlschlag blockiert die Fensterpflege nicht: die Liste bleibt sichtbar, nur Leisten-
   * Tabelle und Auswahl sind leer.
   */
  private loadHelpers(): void {
    this.entityApi.getEntities('INPUT_BOOLEAN', MANUAL_SOURCE).subscribe({
      next: entities => this.helpers.set(entities.map(helperOptionFrom)),
      error: () => this.helpers.set([])
    });
  }

  /** Nur Leisten-Mitglieder duerfen ein Fenster bekommen — der Schnellzugriff ist eine Teilmenge der Leiste. */
  get barHelpers(): HelperOption[] {
    return this.helpers().filter(helper => helper.inBar);
  }

  /**
   * Nimmt einen Helfer in die Modus-Leiste auf oder heraus. Fuer einen Katalog-Modus heisst
   * "raus" NEVER und "rein" AUTO (sein Standard); fuer einen gewoehnlichen Helfer heisst
   * "rein" ALWAYS und "raus" AUTO. So bleibt nur dort eine Regel stehen, wo vom Standard
   * abgewichen wird. Die Antwort traegt die neue Regel, daraus wird der Status neu berechnet.
   */
  setInBar(helper: HelperOption, inBar: boolean): void {
    if (this.barPending().has(helper.entityId)) {
      return;
    }
    const visibility: TileVisibility = helper.isMode
      ? (inBar ? 'AUTO' : 'NEVER')
      : (inBar ? 'ALWAYS' : 'AUTO');
    this.barPending.update(set => new Set(set).add(helper.entityId));
    this.errorMessage.set(null);
    this.entityApi.setTileVisibility(helper.entityId, MODES_TILE_KEY, visibility).subscribe({
      next: updated => {
        this.helpers.update(list => list.map(item =>
          item.entityId === updated.entityId ? helperOptionFrom(updated) : item));
        this.releaseBarPending(helper.entityId);
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage.set(this.messageFrom(error));
        this.releaseBarPending(helper.entityId);
      }
    });
  }

  private releaseBarPending(entityId: string): void {
    this.barPending.update(set => {
      const next = new Set(set);
      next.delete(entityId);
      return next;
    });
  }

  get editing(): boolean {
    return this.form.id !== null;
  }

  /** Anzeigename eines Fensters; ohne zugehoerigen Helfer die rohe Entity-ID. */
  label(window: ModeQuickAccess): string {
    return window.displayName ?? window.entityId;
  }

  /** "20:00:00" -> "20:00"; ein `<input type="time">` erwartet die kurze Form. null bleibt leer. */
  shortTime(time: string | null): string {
    return time?.slice(0, 5) ?? '';
  }

  /** True, wenn der Eintrag kein Zeitfenster hat und damit dauerhaft gilt. */
  isAlways(window: ModeQuickAccess): boolean {
    return window.fromTime === null && window.toTime === null;
  }

  /** Anzeige der Zeitspalte: das Fenster, oder "immer" ohne Fenster. */
  windowLabel(window: ModeQuickAccess): string {
    return this.isAlways(window)
      ? 'immer'
      : `${this.shortTime(window.fromTime)} – ${this.shortTime(window.toTime)}`;
  }

  startEdit(window: ModeQuickAccess): void {
    this.errorMessage.set(null);
    const always = this.isAlways(window);
    this.form = {
      id: window.id,
      entityId: window.entityId,
      always,
      // Ohne Fenster bleiben die Vorgabezeiten stehen, damit ein Abwaehlen von "Immer" sofort
      // ein brauchbares Fenster zeigt statt zweier leerer Felder.
      fromTime: always ? '20:00' : this.shortTime(window.fromTime),
      toTime: always ? '06:00' : this.shortTime(window.toTime),
      active: window.active
    };
  }

  resetForm(): void {
    this.form = emptyForm();
    this.errorMessage.set(null);
  }

  save(): void {
    if (!this.form.entityId) {
      this.errorMessage.set('Es ist kein Helfer ausgewählt.');
      return;
    }
    if (!this.form.always && (!this.form.fromTime || !this.form.toTime)) {
      this.errorMessage.set('Beginn und Ende müssen gesetzt sein — oder „Immer anzeigen“ wählen.');
      return;
    }
    if (!this.form.always && this.form.fromTime === this.form.toTime) {
      this.errorMessage.set('Beginn und Ende dürfen nicht gleich sein — das Fenster wäre leer.');
      return;
    }
    const request: ModeQuickAccessRequest = {
      entityId: this.form.entityId,
      // "Immer" wird als fehlendes Fenster uebertragen — beide Zeiten null, nie nur eine.
      fromTime: this.form.always ? null : this.form.fromTime,
      toTime: this.form.always ? null : this.form.toTime,
      active: this.form.active
    };
    const id = this.form.id;
    this.saving.set(true);
    this.errorMessage.set(null);
    const call = id === null ? this.api.create(request) : this.api.update(id, request);
    call.subscribe({
      next: () => {
        // Die Helfer-Liste dient hier nur dem Dropdown — ein neues/geaendertes Fenster
        // aendert daran nichts, ein Nachladen waere ein Request ohne Wirkung.
        this.load(() => {
          this.saving.set(false);
          this.resetForm();
        });
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
