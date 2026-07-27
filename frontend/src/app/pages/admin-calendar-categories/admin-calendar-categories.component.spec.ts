import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminCalendarCategoriesComponent } from './admin-calendar-categories.component';
import { CalendarCategory } from '../../models/calendar-category.model';

const CATEGORIES_URL = '/api/v1/calendar/categories';

/**
 * Loest ein Farb-Token aus `styles.scss` in die Schreibweise auf, die `getComputedStyle`
 * liefert. Die Werte werden dadurch nicht im Test dupliziert — geprueft wird, dass der
 * Knopf das gemeinte Token traegt, nicht ein zufaellig gleiches Rot.
 */
function resolvedColor(token: string): string {
  const probe = document.createElement('div');
  probe.style.backgroundColor = `var(${token})`;
  document.body.appendChild(probe);
  const value = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return value;
}

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
    // Die Servermeldung nennt nur die Anzahl — ohne den Namen zeigt der Banner nicht,
    // worauf sich das Angebot bezieht.
    expect(banner?.textContent).toContain('Arzttermin');
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
      name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 10, active: true
    });
    created.flush({ id: 3, key: 'geburtstag', name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 0, active: true });
    httpMock.expectOne(CATEGORIES_URL).flush([]);
  });

  it('nimmt den Konflikt-Banner weg, sobald eine andere Kategorie bearbeitet wird', async () => {
    await loadWith([ARZT, ALTLAST]);
    failingDelete(1, 409, 'Die Kategorie wird von 4 Termin(en) genutzt und kann nicht geloescht werden.');
    expect(el.querySelector('.admin-calendar-categories__blocked')).toBeTruthy();

    // „Bearbeiten" auf der ZWEITEN Zeile: Ohne Aufraeumen zeigte der Banner weiter
    // Kategorie 1 an, und „Stattdessen deaktivieren" traefe sie auch.
    (rows()[1].querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el.querySelector('.admin-calendar-categories__blocked')).toBeFalsy();
  });

  it('raeumt das Formular, wenn die gerade bearbeitete Kategorie geloescht wird', async () => {
    await loadWith([ARZT]);

    (rows()[0].querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Kategorie bearbeiten');

    spyOn(window, 'confirm').and.returnValue(true);
    (rows()[0].querySelector('.admin-calendar-categories__delete') as HTMLButtonElement).click();
    httpMock.expectOne(`${CATEGORIES_URL}/1`).flush(null);
    httpMock.expectOne(CATEGORIES_URL).flush([]);
    fixture.detectChanges();

    // Sonst stuende dort weiter „Kategorie bearbeiten", und Speichern schickte ein PUT
    // auf eine geloeschte Id.
    expect(el.textContent).not.toContain('Kategorie bearbeiten');
    expect(fixture.componentInstance.form.id).toBeNull();
  });

  it('schlaegt fuer eine neue Kategorie eine Reihenfolge hinter allen bestehenden vor', async () => {
    await loadWith([ARZT, ALTLAST]);

    const sortOrder = el.querySelector('input[name="sortOrder"]') as HTMLInputElement;
    expect(sortOrder.value).toBe('12');

    const name = el.querySelector('input[name="name"]') as HTMLInputElement;
    name.value = 'Geburtstag';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    // Mit der 0 stuende die neue Kategorie in JEDEM Termindialog vor den gepflegten.
    const created = httpMock.expectOne(CATEGORIES_URL);
    expect(created.request.body.sortOrder).toBe(12);
    created.flush({ id: 3, key: 'geburtstag', name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 12, active: true });
    httpMock.expectOne(CATEGORIES_URL).flush([ARZT, ALTLAST]);
  });

  it('laesst waehrend des Nachladens kein zweites Anlegen zu', async () => {
    await loadWith([]);

    const name = el.querySelector('input[name="name"]') as HTMLInputElement;
    name.value = 'Geburtstag';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = el.querySelector('button[type="submit"]') as HTMLButtonElement;
    submit.click();
    httpMock.expectOne(r => r.method === 'POST')
      .flush({ id: 3, key: 'geburtstag', name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 10, active: true });
    fixture.detectChanges();

    // Der Nachlade-GET laeuft noch. Bis er da ist, traegt das Formular unveraendert die
    // eingegebenen Werte — waere der Knopf hier schon wieder frei, legte ein zweiter
    // Klick dieselbe Kategorie ein zweites Mal an.
    expect(submit.disabled).toBeTrue();
    submit.click();
    httpMock.expectNone(r => r.method === 'POST');

    httpMock.expectOne(CATEGORIES_URL).flush([]);
    fixture.detectChanges();
    expect(submit.disabled).toBeFalse();
  });

  it('behaelt eine selbst eingetragene Reihenfolge ueber ein Nachladen hinweg', async () => {
    await loadWith([ARZT]);

    const sortOrder = el.querySelector('input[name="sortOrder"]') as HTMLInputElement;
    sortOrder.value = '5';
    sortOrder.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // Nachladen ohne Zutun des Nutzers am Formular — hier ueber das Umschalten in der Zeile.
    (rows()[0].querySelector('.admin-calendar-categories__toggle-active') as HTMLButtonElement).click();
    httpMock.expectOne(`${CATEGORIES_URL}/1`).flush({ ...ARZT, active: false });
    httpMock.expectOne(CATEGORIES_URL).flush([{ ...ARZT, active: false }]);
    fixture.detectChanges();

    expect(fixture.componentInstance.form.sortOrder).toBe(5);
  });

  it('nimmt den Konflikt-Banner weg, wenn stattdessen eine neue Kategorie angelegt wird', async () => {
    await loadWith([ARZT]);
    failingDelete(1, 409, 'Die Kategorie wird von 4 Termin(en) genutzt und kann nicht geloescht werden.');
    expect(el.querySelector('.admin-calendar-categories__blocked')).toBeTruthy();

    const name = el.querySelector('input[name="name"]') as HTMLInputElement;
    name.value = 'Geburtstag';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    httpMock.expectOne(r => r.method === 'POST')
      .flush({ id: 3, key: 'geburtstag', name: 'Geburtstag', color: '#64b5f6', icon: null, sortOrder: 11, active: true });
    httpMock.expectOne(CATEGORIES_URL).flush([ARZT]);
    fixture.detectChanges();

    // Der einzige Pfad, auf dem allein resetForm aufraeumt.
    expect(el.querySelector('.admin-calendar-categories__blocked')).toBeFalsy();
  });

  it('lehnt einen leeren Namen ohne Anfrage ab', async () => {
    await loadWith([]);

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(CATEGORIES_URL);
    expect(el.querySelector('.admin-calendar-categories__error')?.textContent)
      .toContain('Der Name darf nicht leer sein.');
  });

  it('zeigt die Tabelle nach einem geglueckten Nachladen wieder', () => {
    fixture.detectChanges();
    httpMock.expectOne(CATEGORIES_URL)
      .flush({ message: 'Kurzer Aussetzer.' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('.admin-calendar-categories__table')).toBeFalsy();

    fixture.componentInstance.load();
    httpMock.expectOne(CATEGORIES_URL).flush([ARZT]);
    fixture.detectChanges();

    // Ohne das Zuruecksetzen von loadFailed bliebe die Seite bis zum Neuladen blind.
    expect(el.querySelector('.admin-calendar-categories__table')).toBeTruthy();
  });

  it('laesst die Tabelle waehrend eines Nachladens stehen', async () => {
    await loadWith([ARZT]);

    (rows()[0].querySelector('.admin-calendar-categories__toggle-active') as HTMLButtonElement).click();
    httpMock.expectOne(`${CATEGORIES_URL}/1`).flush({ ...ARZT, active: false });
    fixture.detectChanges();

    // Der Abruf laeuft noch: Wuerde `loading` erneut gesetzt, verschwaende die Tabelle
    // und das Layout spraenge bei jeder Aktion.
    expect(el.querySelector('.admin-calendar-categories__table')).toBeTruthy();
    httpMock.expectOne(CATEGORIES_URL).flush([{ ...ARZT, active: false }]);
  });

  it('laesst das Formular in Ruhe, wenn eine andere Kategorie umgeschaltet wird', async () => {
    // Beide Kategorien sind aktiv. Waeren sie es nicht, liefe der Test ins Leere: Die
    // umgeschaltete landete dann genau in dem Zustand, den das Formular ohnehin hat, und
    // ein faelschlich mitgeschaltetes Formular waere nicht zu unterscheiden.
    const sport: CalendarCategory = { ...ARZT, id: 3, key: 'sport', name: 'Sport', sortOrder: 3 };
    await loadWith([ARZT, sport]);

    (rows()[0].querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (rows()[1].querySelector('.admin-calendar-categories__toggle-active') as HTMLButtonElement).click();
    httpMock.expectOne(`${CATEGORIES_URL}/3`).flush({ ...sport, active: false });
    httpMock.expectOne(CATEGORIES_URL).flush([ARZT, { ...sport, active: false }]);
    fixture.detectChanges();

    expect(fixture.componentInstance.form.id).toBe(1);
    expect(fixture.componentInstance.form.active).toBeTrue();
  });

  it('faerbt die Knoepfe nach ihrer Rolle', async () => {
    await loadWith([ARZT]);

    const edit = rows()[0].querySelector('button') as HTMLButtonElement;
    const remove = rows()[0].querySelector('.admin-calendar-categories__delete') as HTMLButtonElement;

    // Beide Werte festgenagelt, nicht nur ihre Ungleichheit: Faellt die Grundregel aus,
    // bekaeme „Bearbeiten" den Browser-Default und „Loeschen" bliebe rot — verschieden,
    // aber roh. Faellt der Modifier aus, waeren beide primaerblau.
    expect(getComputedStyle(edit).backgroundColor).toBe(resolvedColor('--color-primary'));
    expect(getComputedStyle(remove).backgroundColor).toBe(resolvedColor('--color-error'));
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
