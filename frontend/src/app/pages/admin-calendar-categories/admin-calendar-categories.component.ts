import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconPickerComponent } from '../../components/icon-picker/icon-picker.component';
import { CalendarCategoryApiError, CalendarCategoryService } from '../../services/calendar-category.service';
import { CalendarCategory, CalendarCategoryRequest } from '../../models/calendar-category.model';

/**
 * Zustand des Anlege-/Bearbeiten-Formulars.
 *
 * Bewusst nicht `extends CalendarCategoryRequest`: zwei Felder haben in der Oberflaeche
 * einen anderen Typ als im Request, und beide Abweichungen sind Fehlerquellen, wenn man
 * sie nicht benennt (siehe die Feldkommentare). Die Umwandlung passiert an genau einer
 * Stelle — {@link AdminCalendarCategoriesComponent.toRequest}.
 */
interface CategoryFormState {
  /** null = Anlegen, sonst die Id der bearbeiteten Kategorie. */
  id: number | null;
  /** Nur zur Anzeige — der Schluessel ist unveraenderlich und wird nie gesendet. */
  key: string | null;
  name: string;
  color: string;
  /**
   * Leer = kein Icon. Im Request ist das Feld `string | null`; der IconPicker arbeitet
   * dagegen mit '' und kennt kein null. Beim Absenden wird aus '' wieder null.
   */
  icon: string;
  /**
   * Nullable, obwohl der Request eine Zahl verlangt: Angulars NumberValueAccessor macht
   * aus einem geleerten Zahlenfeld `null`, nicht 0 oder NaN. Ohne diesen Typ stuende hier
   * an TypeScript vorbei ein null, das ungeprueft ans Backend ginge.
   */
  sortOrder: number | null;
  active: boolean;
}

/** Ein abgelehnter Loeschversuch samt der Meldung des Servers (nennt die Terminanzahl). */
interface DeleteConflict {
  category: CalendarCategory;
  message: string;
}

const DEFAULT_COLOR = '#64b5f6';
/**
 * Abstand zwischen zwei vorgeschlagenen Reihenfolgen. Laesst Platz, um spaeter eine
 * Kategorie dazwischen zu schieben, ohne alle anderen anzufassen.
 */
const SORT_ORDER_STEP = 10;

function emptyForm(sortOrder: number): CategoryFormState {
  return { id: null, key: null, name: '', color: DEFAULT_COLOR, icon: '', sortOrder, active: true };
}

/**
 * Admin-Seite „Kalender-Kategorien": Pflege der Stammdaten, auf die Termine und
 * Automatisierungen sich beziehen.
 */
@Component({
  selector: 'app-admin-calendar-categories',
  standalone: true,
  imports: [CommonModule, FormsModule, IconPickerComponent],
  templateUrl: './admin-calendar-categories.component.html',
  styleUrl: './admin-calendar-categories.component.scss'
})
export class AdminCalendarCategoriesComponent implements OnInit {
  private readonly categoryApi = inject(CalendarCategoryService);

  readonly categories = signal<CalendarCategory[]>([]);
  /**
   * Nur der erste Abruf blendet die Tabelle aus. Spaetere Aktualisierungen — nach jedem
   * Speichern, Umschalten und Loeschen — lassen sie stehen; sonst springt das Layout bei
   * jeder Aktion.
   */
  readonly loading = signal(true);
  /**
   * Bei fehlgeschlagenem Laden bleibt die Tabelle verborgen. Sonst behauptete sie mit
   * „Noch keine Kategorien angelegt." das Gegenteil dessen, was der Fall ist — und wer
   * das glaubt, legt Kategorien ein zweites Mal an.
   */
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);
  /** Gesetzt, solange ein abgelehnter Loeschversuch das Deaktivieren als Ausweg anbietet. */
  readonly blocked = signal<DeleteConflict | null>(null);

  form: CategoryFormState = emptyForm(SORT_ORDER_STEP);

  ngOnInit(): void {
    this.load();
  }

  /**
   * Laedt die Liste neu. `afterLoad` laeuft, sobald die Antwort da ist — auch im
   * Fehlerfall, damit ein bereits gespeichertes Formular nicht stehen bleibt und ein
   * zweites Mal abgeschickt werden kann.
   *
   * `loading` wird hier bewusst nicht wieder auf true gesetzt: es markiert nur den
   * allerersten Abruf.
   */
  load(afterLoad?: () => void): void {
    this.categoryApi.list().subscribe({
      next: categories => {
        this.categories.set(categories);
        this.loadFailed.set(false);
        this.loading.set(false);
        afterLoad?.();
        this.proposeSortOrder();
      },
      error: (err: Error) => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set(err.message);
        afterLoad?.();
      }
    });
  }

  get editing(): boolean {
    return this.form.id !== null;
  }

  startEdit(category: CalendarCategory): void {
    this.errorMessage.set(null);
    // Der Konflikt-Banner gehoert zu der Kategorie, deren Loeschung scheiterte. Bliebe er
    // beim Wechsel ins Formular einer anderen stehen, deaktivierte „Stattdessen
    // deaktivieren" sichtbar die eine und tatsaechlich die andere.
    this.blocked.set(null);
    this.form = {
      id: category.id,
      key: category.key,
      name: category.name,
      color: category.color,
      icon: category.icon ?? '',
      sortOrder: category.sortOrder,
      active: category.active
    };
  }

  resetForm(): void {
    this.form = emptyForm(this.nextSortOrder());
    this.errorMessage.set(null);
    this.blocked.set(null);
  }

  /**
   * Uebernimmt den Reihenfolge-Vorschlag in ein noch unberuehrtes Anlege-Formular.
   *
   * Der haeufigste Weg zu einer neuen Kategorie fuehrt gar nicht ueber `resetForm()` —
   * Seite oeffnen, Namen tippen, anlegen. Ohne diesen Schritt bliebe dort der beim Bauen
   * der Komponente geratene Wert stehen, der die geladene Liste noch nicht kannte.
   * Sobald der Nutzer angefangen hat oder eine Kategorie bearbeitet, wird nichts mehr
   * angefasst: ein Nachladen darf keine Eingabe ueberschreiben.
   */
  private proposeSortOrder(): void {
    if (this.form.id === null && this.form.name === '') {
      this.form.sortOrder = this.nextSortOrder();
    }
  }

  /**
   * Vorschlag fuer die Reihenfolge einer neuen Kategorie: hinter allen bestehenden.
   *
   * Ohne ihn bekaeme jede neue Kategorie die 0 und stuende damit nicht nur in dieser
   * Tabelle ganz oben, sondern auch in der Kategorieauswahl **jedes** Termindialogs vor
   * den gepflegten — der Server sortiert nach `sortOrder ASC, name ASC`.
   */
  private nextSortOrder(): number {
    const highest = this.categories()
      .reduce((max, category) => Math.max(max, category.sortOrder), 0);
    return highest + SORT_ORDER_STEP;
  }

  save(): void {
    if (!this.form.name.trim()) {
      this.errorMessage.set('Der Name darf nicht leer sein.');
      return;
    }
    const request = this.toRequest(this.form);
    const id = this.form.id;
    this.saving.set(true);
    this.errorMessage.set(null);
    const call = id === null
      ? this.categoryApi.create(request)
      : this.categoryApi.update(id, request);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        // Erst nach dem Neuladen zuruecksetzen: der Reihenfolge-Vorschlag fuer den
        // naechsten Eintrag muss den gerade gespeicherten schon kennen, sonst schlaegt er
        // genau dessen Wert noch einmal vor.
        this.load(() => this.resetForm());
      },
      error: (err: Error) => {
        this.saving.set(false);
        this.errorMessage.set(err.message);
      }
    });
  }

  /**
   * Schaltet eine Kategorie aktiv/inaktiv. Auch der Ausweg aus dem Loeschkonflikt laeuft
   * hierueber — deshalb loescht ein Erfolg den Konfliktzustand.
   */
  setActive(category: CalendarCategory, active: boolean): void {
    this.errorMessage.set(null);
    this.categoryApi.update(category.id, this.requestFrom(category, active)).subscribe({
      next: () => {
        // Steht dieselbe Kategorie gerade im Formular, muss der Schalter dort mitwandern.
        // Sonst traegt das Formular noch den alten Wert, und das naechste Speichern
        // machte diese Umschaltung wieder rueckgaengig.
        if (this.form.id === category.id) {
          this.form.active = active;
        }
        this.blocked.set(null);
        this.load();
      },
      error: (err: Error) => this.errorMessage.set(err.message)
    });
  }

  remove(category: CalendarCategory): void {
    if (!confirm(`Kategorie „${category.name}“ endgültig löschen?`)) {
      return;
    }
    this.errorMessage.set(null);
    this.blocked.set(null);
    this.categoryApi.delete(category.id).subscribe({
      next: () => {
        // Stand die geloeschte Kategorie im Formular, ist dessen Id jetzt tot: Die
        // Ueberschrift behauptete weiter „Kategorie bearbeiten", und Speichern schickte
        // ein PUT auf eine geloeschte Id.
        if (this.form.id === category.id) {
          this.resetForm();
        }
        this.load();
      },
      error: (err: Error) => {
        // Nur der Loeschschutz (409) hat einen Ausweg. Bei jedem anderen Fehler — Netz weg,
        // Sitzung abgelaufen — waere das Deaktivieren-Angebot ein Versprechen, das
        // genauso fehlschluege.
        if (err instanceof CalendarCategoryApiError && err.status === 409) {
          this.blocked.set({ category, message: err.message });
          return;
        }
        this.errorMessage.set(err.message);
      }
    });
  }

  dismissBlocked(): void {
    this.blocked.set(null);
  }

  /** Formularzustand -> Request. */
  private toRequest(state: CategoryFormState): CalendarCategoryRequest {
    return {
      name: state.name.trim(),
      color: state.color,
      icon: state.icon.trim() || null,
      // Ein geleertes Zahlenfeld liefert null; die 0 ist der Default der Spalte.
      sortOrder: state.sortOrder ?? 0,
      active: state.active
    };
  }

  /**
   * Vollstaendiger Request aus einer bestehenden Kategorie, mit ausgetauschtem `active`.
   *
   * Der Server liest ein fehlendes `active` als „aktiv". Ein Teil-PUT wuerde eine
   * deaktivierte Kategorie also stillschweigend wieder aktivieren — sie tauchte ohne
   * jede Meldung wieder in der Terminauswahl auf. Deshalb wird hier immer der komplette
   * Satz gesendet.
   */
  private requestFrom(category: CalendarCategory, active: boolean): CalendarCategoryRequest {
    return {
      name: category.name,
      color: category.color,
      icon: category.icon,
      sortOrder: category.sortOrder,
      active
    };
  }
}
