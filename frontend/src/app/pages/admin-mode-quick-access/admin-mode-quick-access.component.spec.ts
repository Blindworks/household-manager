import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminModeQuickAccessComponent } from './admin-mode-quick-access.component';
import { ModeQuickAccess } from '../../models/mode-quick-access.model';
import { EntityState, TileVisibility } from '../../models/entity-state.model';

const WINDOWS_URL = '/api/v1/mode-quick-access';
/** Das Dropdown laedt alle Helfer vom Typ INPUT_BOOLEAN — die Query-Parameter sind Teil des Vertrags. */
const HELPERS_URL = '/api/v1/entities?domain=INPUT_BOOLEAN&source=MANUAL';

function helper(ref: string, displayName: string, attributes: Record<string, unknown>,
                tileVisibility?: Record<string, TileVisibility>): EntityState {
  return {
    entityId: `input_boolean.manual_${ref}`,
    domain: 'INPUT_BOOLEAN',
    source: 'MANUAL',
    sourceRef: ref,
    friendlyName: displayName,
    displayName,
    state: 'off',
    attributes,
    tileVisibility,
    lastChanged: '2026-09-15T06:00:00',
    lastUpdated: '2026-09-15T06:00:00'
  };
}

const NACHTMODUS = helper('nachtmodus', 'Nachtmodus', { icon: 'nights_stay', mode: true });
const ABWESEND = helper('abwesend', 'Abwesend', { icon: 'exit_to_app', mode: true });
/** Ein gewoehnlicher Helfer, per ALWAYS in die Modus-Leiste geholt — damit im Schnellzugriff waehlbar. */
const KAMIN = helper('kamin', 'Kamin', { icon: 'fireplace' }, { modes: 'ALWAYS' });
/** Ein gewoehnlicher Helfer ohne Regel: nicht in der Leiste, also auch nicht waehlbar. */
const URLAUB = helper('urlaub', 'Urlaub', { icon: 'beach_access' });

const NACHT_FENSTER: ModeQuickAccess = {
  id: 1,
  entityId: 'input_boolean.manual_nachtmodus',
  displayName: 'Nachtmodus',
  fromTime: '20:00:00',
  toTime: '06:00:00',
  active: true
};

describe('AdminModeQuickAccessComponent', () => {
  let fixture: ComponentFixture<AdminModeQuickAccessComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminModeQuickAccessComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminModeQuickAccessComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  /**
   * Startet die Seite und beantwortet beide Abrufe. Das `whenStable()` ist nicht optional:
   * innerhalb eines `<form>` registriert NgForm jedes NgModel erst in einem Microtask —
   * vorher veraendert ein `input`-Ereignis aus dem Test das Formular nicht.
   */
  async function loadWith(windows: ModeQuickAccess[],
                          helpers: EntityState[] = [NACHTMODUS, ABWESEND, KAMIN, URLAUB]) {
    fixture.detectChanges();
    httpMock.expectOne(WINDOWS_URL).flush(windows);
    httpMock.expectOne(HELPERS_URL).flush(helpers);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  /** Zeilen der Zeitfenster-Tabelle (nicht der Leisten-Tabelle). */
  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll(
      '.admin-mode-quick-access__table:not(.admin-mode-quick-access__bar-table) tbody tr'));
  }

  function barRows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-mode-quick-access__bar-table tbody tr'));
  }

  function barToggle(row: HTMLElement): HTMLInputElement {
    return row.querySelector('.admin-mode-quick-access__bar-toggle') as HTMLInputElement;
  }

  function setInput(name: string, value: string): void {
    const input = el.querySelector(`[name="${name}"]`) as HTMLInputElement | HTMLSelectElement;
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  }

  it('zeigt die gepflegten Zeitfenster', async () => {
    await loadWith([NACHT_FENSTER]);

    expect(el.textContent).toContain('Nachtmodus');
    expect(el.textContent).toContain('20:00');
    expect(el.textContent).toContain('06:00');
  });

  /**
   * Der Schnellzugriff ist eine Teilmenge der Modus-Leiste: waehlbar sind Katalog-Modi (ohne
   * NEVER) und per ALWAYS hineingeholte Helfer — ein Helfer ohne Regel fehlt.
   */
  it('bietet nur Helfer zur Auswahl an, die in der Modus-Leiste stehen', async () => {
    await loadWith([]);

    const options = Array.from(el.querySelectorAll('[name="entityId"] option'))
      .map(option => option.textContent?.trim());
    expect(options).toContain('Nachtmodus');
    expect(options).toContain('Abwesend');
    expect(options).toContain('Kamin');
    expect(options).not.toContain('Urlaub');
  });

  it('zeigt alle Helfer mit ihrem Leisten-Status', async () => {
    await loadWith([]);

    const status = barRows().map(row =>
      `${row.querySelector('td')?.textContent?.trim()}:${barToggle(row).checked}`);
    expect(status).toEqual(['Nachtmodus:true', 'Abwesend:true', 'Kamin:true', 'Urlaub:false']);
  });

  /**
   * Ein Katalog-Modus wird per NEVER herausgenommen (sein Standard ist "drin"), ein gewoehnlicher
   * Helfer per ALWAYS hineingeholt (sein Standard ist "draussen"). Zurueck zum Standard heisst
   * jeweils AUTO — so steht nur dort eine Regel, wo vom Standard abgewichen wird.
   */
  it('nimmt einen Modus per NEVER aus der Leiste und holt einen Helfer per ALWAYS hinein', async () => {
    await loadWith([]);

    barToggle(barRows()[0]).click();
    const modeUpdate = httpMock.expectOne('/api/v1/entities/input_boolean.manual_nachtmodus/tiles/modes');
    expect(modeUpdate.request.method).toBe('PUT');
    expect(modeUpdate.request.body).toEqual({ visibility: 'NEVER' });
    modeUpdate.flush({ ...NACHTMODUS, tileVisibility: { modes: 'NEVER' } });
    fixture.detectChanges();
    expect(barToggle(barRows()[0]).checked).toBeFalse();

    barToggle(barRows()[3]).click();
    const helperUpdate = httpMock.expectOne('/api/v1/entities/input_boolean.manual_urlaub/tiles/modes');
    expect(helperUpdate.request.body).toEqual({ visibility: 'ALWAYS' });
    helperUpdate.flush({ ...URLAUB, tileVisibility: { modes: 'ALWAYS' } });
    fixture.detectChanges();
    expect(barToggle(barRows()[3]).checked).toBeTrue();

    // Der hineingeholte Helfer ist jetzt auch im Schnellzugriff waehlbar, der Modus nicht mehr.
    const options = Array.from(el.querySelectorAll('[name="entityId"] option'))
      .map(option => option.textContent?.trim());
    expect(options).toContain('Urlaub');
    expect(options).not.toContain('Nachtmodus');
  });

  it('setzt einen Modus per AUTO zurueck in die Leiste', async () => {
    await loadWith([], [helper('nachtmodus', 'Nachtmodus', { mode: true }, { modes: 'NEVER' })]);

    expect(barToggle(barRows()[0]).checked).toBeFalse();
    barToggle(barRows()[0]).click();

    const update = httpMock.expectOne('/api/v1/entities/input_boolean.manual_nachtmodus/tiles/modes');
    expect(update.request.body).toEqual({ visibility: 'AUTO' });
    update.flush(NACHTMODUS);
  });

  it('legt ein Zeitfenster mit den Formularwerten an', async () => {
    await loadWith([]);

    setInput('entityId', 'input_boolean.manual_nachtmodus');
    setInput('fromTime', '20:00');
    setInput('toTime', '06:00');

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const created = httpMock.expectOne(WINDOWS_URL);
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({
      entityId: 'input_boolean.manual_nachtmodus',
      fromTime: '20:00',
      toTime: '06:00',
      active: true
    });
    created.flush(NACHT_FENSTER);
    httpMock.expectOne(WINDOWS_URL).flush([NACHT_FENSTER]);
  });

  it('lehnt ein Speichern ohne Helfer ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('fromTime', '20:00');
    setInput('toTime', '06:00');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(WINDOWS_URL);
    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Es ist kein Helfer ausgewählt.');
  });

  /**
   * "Immer anzeigen" wird als fehlendes Fenster uebertragen: BEIDE Zeiten null. Die
   * Zeitfelder tragen dabei weiterhin ihre Vorgabewerte — sie duerfen nicht mitgesendet werden.
   */
  it('legt einen Eintrag ohne Zeitfenster an, wenn "Immer anzeigen" gewaehlt ist', async () => {
    await loadWith([]);

    setInput('entityId', 'input_boolean.manual_kamin');
    const always = el.querySelector('[name="always"]') as HTMLInputElement;
    always.click();
    fixture.detectChanges();
    // NgModel schreibt [disabled] erst in einem Microtask ans DOM — ohne whenStable()
    // saehe der Test das Feld noch aktiv, obwohl es gleich darauf gesperrt ist.
    await fixture.whenStable();

    expect((el.querySelector('[name="fromTime"]') as HTMLInputElement).disabled).toBeTrue();
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const created = httpMock.expectOne(WINDOWS_URL);
    expect(created.request.body).toEqual({
      entityId: 'input_boolean.manual_kamin',
      fromTime: null,
      toTime: null,
      active: true
    });
    created.flush({ ...NACHT_FENSTER, id: 9, entityId: 'input_boolean.manual_kamin',
      displayName: 'Kamin', fromTime: null, toTime: null });
    httpMock.expectOne(WINDOWS_URL).flush([]);
  });

  it('zeigt einen Eintrag ohne Zeitfenster als "immer"', async () => {
    await loadWith([{ ...NACHT_FENSTER, fromTime: null, toTime: null }]);

    expect(rows()[0].textContent).toContain('immer');
    expect(rows()[0].textContent).not.toContain('20:00');
  });

  /** Beim Bearbeiten eines "immer"-Eintrags muss die Checkbox gesetzt sein, sonst wuerde ein Speichern das Fenster erfinden. */
  it('uebernimmt "immer" beim Bearbeiten ins Formular', async () => {
    await loadWith([{ ...NACHT_FENSTER, fromTime: null, toTime: null }]);

    (rows()[0].querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.form.always).toBeTrue();
    expect((el.querySelector('[name="always"]') as HTMLInputElement).checked).toBeTrue();
  });

  it('lehnt gleichen Beginn und gleiches Ende ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('entityId', 'input_boolean.manual_nachtmodus');
    setInput('fromTime', '20:00');
    setInput('toTime', '20:00');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(WINDOWS_URL);
    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Beginn und Ende');
  });

  /**
   * Der Server liest ein fehlendes `active` als „aktiv". Ein Teil-PUT wuerde ein
   * deaktiviertes Fenster also stillschweigend reaktivieren.
   */
  it('schaltet ein Fenster mit vollstaendigem Request aktiv/inaktiv', async () => {
    await loadWith([NACHT_FENSTER]);

    (rows()[0].querySelector('.admin-mode-quick-access__toggle-active') as HTMLButtonElement).click();

    const update = httpMock.expectOne(`${WINDOWS_URL}/1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      entityId: 'input_boolean.manual_nachtmodus',
      fromTime: '20:00:00',
      toTime: '06:00:00',
      active: false
    });
    update.flush({ ...NACHT_FENSTER, active: false });
    httpMock.expectOne(WINDOWS_URL).flush([{ ...NACHT_FENSTER, active: false }]);
  });

  it('loescht ein Fenster nach Bestaetigung', async () => {
    await loadWith([NACHT_FENSTER]);
    spyOn(window, 'confirm').and.returnValue(true);

    (rows()[0].querySelector('.admin-mode-quick-access__delete') as HTMLButtonElement).click();

    const deleted = httpMock.expectOne(`${WINDOWS_URL}/1`);
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    httpMock.expectOne(WINDOWS_URL).flush([]);
    fixture.detectChanges();

    // Der Reload liefert eine leere Liste zurueck. Ein reiner Laengencheck auf 0 waere hier
    // falsch: eine leere Tabelle rendert einen Platzhalter-<tr> ("Noch keine Zeitfenster
    // angelegt."), also muss geprueft werden, dass keine Zeile mehr den Nachtmodus zeigt.
    expect(rows().some(row => row.textContent?.includes('Nachtmodus'))).toBeFalse();
  });

  /** Ein Fenster ohne zugehoerigen Helfer bleibt sichtbar — sonst waere es nicht loeschbar. */
  it('zeigt ein verwaistes Fenster mit seiner rohen Entity-ID', async () => {
    await loadWith([{ ...NACHT_FENSTER, id: 5, entityId: 'input_boolean.manual_weg', displayName: null }]);

    expect(el.textContent).toContain('input_boolean.manual_weg');
  });

  it('zeigt einen Serverfehler beim Anlegen an', async () => {
    await loadWith([]);

    setInput('entityId', 'input_boolean.manual_nachtmodus');
    setInput('fromTime', '20:00');
    setInput('toTime', '06:00');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    httpMock.expectOne(WINDOWS_URL).flush(
      { message: 'Fuer diesen Helfer gibt es bereits ein Zeitfenster.' },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('bereits ein Zeitfenster');
  });

  /**
   * loadHelpers() faengt einen Fehlschlag ab und setzt nur eine leere Liste (kein errorMessage,
   * kein loadFailed) — die Fensterpflege soll trotz kaputter Helfer-Liste bedienbar bleiben.
   */
  it('laesst die Fensterpflege nutzbar, wenn nur die Helfer-Liste nicht geladen werden kann', () => {
    fixture.detectChanges();
    httpMock.expectOne(WINDOWS_URL).flush([NACHT_FENSTER]);
    httpMock.expectOne(HELPERS_URL)
      .flush({ message: 'Helfer nicht erreichbar.' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();

    // Die Tabelle ist da und zeigt das geladene Fenster.
    expect(el.querySelector('.admin-mode-quick-access__table')).toBeTruthy();
    expect(rows().length).toBe(1);
    expect(el.textContent).toContain('Nachtmodus');

    // Das Dropdown hat ausser der Platzhalter-Option keine Helfer-Optionen, die Leisten-Tabelle ist leer.
    expect(barRows().length).toBe(1);
    expect(barRows()[0].textContent).toContain('Keine Helfer gefunden.');
    const options = Array.from(el.querySelectorAll('[name="entityId"] option'));
    expect(options.length).toBe(1);
    expect(options[0].textContent?.trim()).toBe('— bitte wählen —');

    // Kein Fehlerbanner, das die Pflege blockieren wuerde — der Fehler betrifft nur das Dropdown.
    expect(el.querySelector('.admin-mode-quick-access__error')).toBeFalsy();
  });

  it('zeigt einen Fehler, wenn das Laden fehlschlaegt', () => {
    fixture.detectChanges();
    httpMock.expectOne(WINDOWS_URL)
      .flush({ message: 'Datenbank nicht erreichbar.' }, { status: 500, statusText: 'Error' });
    httpMock.expectOne(HELPERS_URL).flush([NACHTMODUS]);
    fixture.detectChanges();

    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Datenbank nicht erreichbar.');
    // Die Zeitfenster-Tabelle bleibt weg (eine leere Liste loege); die Leisten-Tabelle haengt
    // an einem eigenen Abruf und steht weiterhin da.
    expect(el.querySelector('.admin-mode-quick-access__table:not(.admin-mode-quick-access__bar-table)'))
      .toBeFalsy();
    expect(el.querySelector('.admin-mode-quick-access__bar-table')).toBeTruthy();
  });
});
