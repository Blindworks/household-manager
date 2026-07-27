import { Injectable, computed, inject, signal } from '@angular/core';
import { CalendarCategoryService } from './calendar-category.service';
import { HouseholdUserService } from './household-user.service';
import { CalendarCategory } from '../models/calendar-category.model';
import { HouseholdUser } from '../models/household-user.model';

/**
 * Meldung bei ausgefallenem Kategorien-Abruf: erst die Folge fuer den Nutzer, dann die
 * Ursache. Die Ursache muss mit - CalendarCategoryService baut sie bereits aus dem
 * Fehlerbody des Backends; verwirft man sie, steht im Supportfall auf dem Wandtablet nur
 * der generische Satz und niemand weiss, ob das Backend, das Netz oder die Anmeldung
 * schuld ist.
 */
function categoryLoadError(cause: string): string {
  return 'Die Kategorien konnten nicht geladen werden. Neue Termine lassen sich gerade '
    + `nicht anlegen. Ursache: ${cause}`;
}

/**
 * Stammdaten des Kalenders: Kategorien und Haushaltsmitglieder.
 *
 * Beide Quellen sind unabhaengig und haben bewusst verschiedene Fehlerpolitiken (siehe
 * load()). Sie liegen hier statt in der Kalenderseite, weil die auch ohne sie schon
 * Raster, Formular, Wiederholungs-Builder und Scope-Rueckfrage traegt — und weil die
 * Kategorien-Administration dieselben Daten braucht.
 */
@Injectable({ providedIn: 'root' })
export class CalendarMasterDataService {
  private readonly categoryApi = inject(CalendarCategoryService);
  private readonly userApi = inject(HouseholdUserService);

  private readonly categoriesState = signal<CalendarCategory[]>([]);
  private readonly usersState = signal<HouseholdUser[]>([]);
  private readonly categoriesLoadedState = signal(false);
  private readonly categoryErrorState = signal<string | null>(null);
  private readonly userErrorState = signal<string | null>(null);

  /** Alle Kategorien inkl. deaktivierter — Bestandstermine brauchen ihre Farbe weiter. */
  readonly categories = this.categoriesState.asReadonly();
  /**
   * Alle Haushaltsmitglieder inkl. deaktivierter. Die deaktivierten werden gebraucht, um
   * eine Zuordnung belegt als "(deaktiviert)" beschriften zu koennen: waere hier nur die
   * aktive Liste, liesse sich "deaktiviert" von "Liste gar nicht geladen" nicht
   * unterscheiden — und bei einem Ausfall stuende an jeder Person eine falsche Behauptung.
   */
  readonly users = this.usersState.asReadonly();

  /** Waehlbare Kategorien eines neuen Termins. */
  readonly activeCategories = computed(() => this.categories().filter(category => category.active));
  /** Waehlbare Personen: an ein ausgezogenes Haushaltsmitglied ordnet niemand mehr zu. */
  readonly activeUsers = computed(() => this.users().filter(user => user.enabled));

  /** false = Kategorien noch nicht geladen; der Termindialog bleibt dann zu. */
  readonly categoriesLoaded = this.categoriesLoadedState.asReadonly();
  readonly categoryError = this.categoryErrorState.asReadonly();
  readonly userError = this.userErrorState.asReadonly();

  /**
   * Laedt beide Quellen. Die Fehlerpolitiken sind bewusst verschieden:
   *
   * Ohne Kategorien bleibt categoriesLoaded false und der Termindialog damit geschlossen —
   * eine leere Auswahlliste saehe aus wie "es gibt keine Kategorien" und verleitete zu
   * falschen Eingaben. Das Banner ist der einzige Hinweis darauf, warum: ohne es waere die
   * Seite von einer funktionierenden nicht zu unterscheiden.
   *
   * Ohne Nutzerliste bleibt der Dialog dagegen benutzbar — ein Termin ohne Personen ist
   * gueltig. Nur eine Meldung braucht es, sonst saehe die leere Liste aus wie "es gibt
   * keine Haushaltsmitglieder".
   */
  load(): void {
    this.categoryApi.list().subscribe({
      next: categories => {
        this.categoriesState.set(categories);
        this.categoriesLoadedState.set(true);
        this.categoryErrorState.set(null);
      },
      error: (err: Error) => this.categoryErrorState.set(categoryLoadError(err.message))
    });
    this.userApi.list().subscribe({
      next: users => {
        this.usersState.set(users);
        this.userErrorState.set(null);
      },
      error: (err: Error) => {
        this.usersState.set([]);
        this.userErrorState.set(err.message);
      }
    });
  }
}
