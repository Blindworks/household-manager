import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminCalendarCategoriesComponent } from './admin-calendar-categories.component';
import { CalendarCategory } from '../../models/calendar-category.model';

const CATEGORIES_URL = '/api/v1/calendar/categories';

const ARZT: CalendarCategory = {
  id: 1, key: 'arzttermin', name: 'Arzttermin', color: '#64b5f6',
  icon: 'local_hospital', sortOrder: 1, active: true
};
/** Bewusst deaktiviert: die stille Reaktivierung beim Speichern ist der gefaehrliche Fall. */
const ALTLAST: CalendarCategory = {
  id: 2, key: 'altlast', name: 'Altlast', color: '#90a4ae',
  icon: null, sortOrder: 2, active: false
};

describe('AdminCalendarCategoriesComponent', () => {
  let fixture: ComponentFixture<AdminCalendarCategoriesComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminCalendarCategoriesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminCalendarCategoriesComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  /**
   * Startet die Seite und beantwortet den Abruf der Liste.
   *
   * Das `whenStable()` ist nicht optional: Innerhalb eines `<form>` registriert NgForm
   * jedes NgModel erst in einem Microtask. Vorher haengt der ValueAccessor noch nicht am
   * Modell, und ein `input`-Ereignis aus dem Test veraendert das Formular nicht — die
   * Tests wuerden lautlos den unveraenderten Ausgangszustand pruefen.
   */
  async function loadWith(categories: CalendarCategory[]): Promise<void> {
    fixture.detectChanges();
    httpMock.expectOne(CATEGORIES_URL).flush(categories);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-calendar-categories__table tbody tr'));
  }

  /** Loeschversuch auf der ersten Zeile, beantwortet mit dem uebergebenen Fehler. */
  function failingDelete(id: number, status: number, message: string): void {
    spyOn(window, 'confirm').and.returnValue(true);
    (rows()[0].querySelector('.admin-calendar-categories__delete') as HTMLButtonElement).click();
    httpMock.expectOne(`${CATEGORIES_URL}/${id}`)
      .flush({ message }, { status, statusText: 'Error' });
    fixture.detectChanges();
  }

  it('zeigt die Kategorien mit ihrem unveraenderlichen Schluessel', async () => {
    await loadWith([ARZT, ALTLAST]);

    const keys = Array.from(el.querySelectorAll('.admin-calendar-categories__key-cell'))
      .map(cell => cell.textContent?.trim());
    expect(keys).toEqual(['arzttermin', 'altlast']);
    expect(el.textContent).toContain('Arzttermin');
    expect(el.textContent).toContain('Altlast');
  });

  it('bietet beim Loeschkonflikt das Deaktivieren als Ausweg an', async () => {
    await loadWith([ARZT]);
    failingDelete(1, 409, 'Die Kategorie wird von 4 Termin(en) genutzt und kann nicht geloescht werden.');

    const banner = el.querySelector('.admin-calendar-categories__blocked');
    expect(banner).toBeTruthy();
    expect(banner?.textContent).toContain('4 Termin(en)');
    expect(el.querySelector('.admin-calendar-categories__deactivate')).toBeTruthy();
  });

  it('deaktiviert nach dem Loeschkonflikt mit vollstaendigem Request', async () => {
    await loadWith([ARZT]);
    failingDelete(1, 409, 'Die Kategorie wird von 4 Termin(en) genutzt und kann nicht geloescht werden.');

    (el.querySelector('.admin-calendar-categories__deactivate') as HTMLButtonElement).click();

    const update = httpMock.expectOne(`${CATEGORIES_URL}/1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      name: 'Arzttermin', color: '#64b5f6', icon: 'local_hospital', sortOrder: 1, active: false
    });
    update.flush({ ...ARZT, active: false });
    httpMock.expectOne(CATEGORIES_URL).flush([{ ...ARZT, active: false }]);
    fixture.detectChanges();

    expect(el.querySelector('.admin-calendar-categories__blocked')).toBeFalsy();
  });

  it('meldet einen Loeschfehler ohne Konflikt, ohne das Deaktivieren anzubieten', async () => {
    await loadWith([ARZT]);
    failingDelete(1, 500, 'Datenbank nicht erreichbar.');

    expect(el.querySelector('.admin-calendar-categories__blocked')).toBeFalsy();
    expect(el.querySelector('.admin-calendar-categories__error')?.textContent)
      .toContain('Datenbank nicht erreichbar.');
  });

  it('haelt eine deaktivierte Kategorie beim Bearbeiten deaktiviert', async () => {
    await loadWith([ALTLAST]);

    (rows()[0].querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    // Der Schluessel steht im Formular, ist aber keine Eingabe.
    const keyField = el.querySelector('.admin-calendar-categories__key') as HTMLInputElement;
    expect(keyField.value).toBe('altlast');
    expect(keyField.readOnly).toBeTrue();

    const name = el.querySelector('input[name="name"]') as HTMLInputElement;
    name.value = 'Altlast neu';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const update = httpMock.expectOne(`${CATEGORIES_URL}/2`);
    expect(update.request.method).toBe('PUT');
    // Ohne mitgesendetes active liest der Server "aktiv" — die Kategorie waere still
    // wieder waehlbar, obwohl niemand sie aktiviert hat.
    expect(update.request.body).toEqual({
      name: 'Altlast neu', color: '#90a4ae', icon: null, sortOrder: 2, active: false
    });
    // Der Schluessel ist unveraenderlich und darf gar nicht erst mitgeschickt werden.
    expect('key' in (update.request.body as Record<string, unknown>)).toBeFalse();
    update.flush({ ...ALTLAST, name: 'Altlast neu' });
    httpMock.expectOne(CATEGORIES_URL).flush([{ ...ALTLAST, name: 'Altlast neu' }]);
  });

  it('legt eine neue Kategorie ohne Icon an', async () => {
    await loadWith([]);

    const name = el.querySelector('input[name="name"]') as HTMLInputElement;
    name.value = '  Geburtstag  ';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const created = httpMock.expectOne(CATEGORIES_URL);
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({
      name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 0, active: true
    });
    created.flush({ id: 3, key: 'geburtstag', name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 0, active: true });
    httpMock.expectOne(CATEGORIES_URL).flush([]);
  });

  it('macht das Umschalten in der Zeile nicht durch spaeteres Speichern rueckgaengig', async () => {
    await loadWith([ARZT]);

    // Kategorie ins Formular holen (dort steht jetzt active = true) …
    (rows()[0].querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    // … und sie danach ueber die Zeile deaktivieren.
    (rows()[0].querySelector('.admin-calendar-categories__toggle-active') as HTMLButtonElement).click();
    httpMock.expectOne(`${CATEGORIES_URL}/1`).flush({ ...ARZT, active: false });
    httpMock.expectOne(CATEGORIES_URL).flush([{ ...ARZT, active: false }]);
    fixture.detectChanges();

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const update = httpMock.expectOne(`${CATEGORIES_URL}/1`);
    expect(update.request.body.active).toBeFalse();
    update.flush({ ...ARZT, active: false });
    httpMock.expectOne(CATEGORIES_URL).flush([{ ...ARZT, active: false }]);
  });

  it('zeigt bei fehlgeschlagenem Laden keine leere Liste', () => {
    fixture.detectChanges();
    httpMock.expectOne(CATEGORIES_URL)
      .flush({ message: 'Datenbank nicht erreichbar.' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();

    expect(el.textContent).not.toContain('Noch keine Kategorien angelegt.');
    expect(el.querySelector('.admin-calendar-categories__error')?.textContent)
      .toContain('Datenbank nicht erreichbar.');
  });

  it('sendet fuer ein geleertes Reihenfolge-Feld eine 0', async () => {
    await loadWith([]);

    const name = el.querySelector('input[name="name"]') as HTMLInputElement;
    name.value = 'Geburtstag';
    name.dispatchEvent(new Event('input'));
    // Angulars NumberValueAccessor macht aus einem geleerten Zahlenfeld null, nicht 0.
    const sortOrder = el.querySelector('input[name="sortOrder"]') as HTMLInputElement;
    sortOrder.value = '';
    sortOrder.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.componentInstance.form.sortOrder).toBeNull();

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const created = httpMock.expectOne(CATEGORIES_URL);
    expect(created.request.body.sortOrder).toBe(0);
    created.flush({ id: 3, key: 'geburtstag', name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 0, active: true });
    httpMock.expectOne(CATEGORIES_URL).flush([]);
  });
});
