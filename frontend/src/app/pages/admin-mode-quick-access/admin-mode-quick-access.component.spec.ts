import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminModeQuickAccessComponent } from './admin-mode-quick-access.component';
import { ModeQuickAccess } from '../../models/mode-quick-access.model';
import { ModeEntity } from '../../models/mode.model';

const WINDOWS_URL = '/api/v1/mode-quick-access';
const MODES_URL = '/api/v1/modes';

const NACHTMODUS: ModeEntity = {
  entityId: 'input_boolean.manual_nachtmodus',
  displayName: 'Nachtmodus',
  icon: 'nights_stay',
  state: 'off',
  quickAccess: false
};

const ABWESEND: ModeEntity = {
  entityId: 'input_boolean.manual_abwesend',
  displayName: 'Abwesend',
  icon: 'exit_to_app',
  state: 'off',
  quickAccess: false
};

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
  async function loadWith(windows: ModeQuickAccess[], modes: ModeEntity[] = [NACHTMODUS, ABWESEND]) {
    fixture.detectChanges();
    httpMock.expectOne(WINDOWS_URL).flush(windows);
    httpMock.expectOne(MODES_URL).flush(modes);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-mode-quick-access__table tbody tr'));
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

  it('bietet die Haus-Modi zur Auswahl an', async () => {
    await loadWith([]);

    const options = Array.from(el.querySelectorAll('[name="entityId"] option'))
      .map(option => option.textContent?.trim());
    expect(options).toContain('Nachtmodus');
    expect(options).toContain('Abwesend');
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
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS, ABWESEND]);
  });

  it('lehnt ein Speichern ohne Modus ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('fromTime', '20:00');
    setInput('toTime', '06:00');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(WINDOWS_URL);
    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Es ist kein Modus ausgewählt.');
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
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS, ABWESEND]);
  });

  it('loescht ein Fenster nach Bestaetigung', async () => {
    await loadWith([NACHT_FENSTER]);
    spyOn(window, 'confirm').and.returnValue(true);

    (rows()[0].querySelector('.admin-mode-quick-access__delete') as HTMLButtonElement).click();

    const deleted = httpMock.expectOne(`${WINDOWS_URL}/1`);
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    httpMock.expectOne(WINDOWS_URL).flush([]);
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS, ABWESEND]);
  });

  /** Ein Fenster ohne zugehoerigen Modus bleibt sichtbar — sonst waere es nicht loeschbar. */
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
      { message: 'Fuer diesen Modus gibt es bereits ein Zeitfenster.' },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('bereits ein Zeitfenster');
  });

  it('zeigt einen Fehler, wenn das Laden fehlschlaegt', () => {
    fixture.detectChanges();
    httpMock.expectOne(WINDOWS_URL)
      .flush({ message: 'Datenbank nicht erreichbar.' }, { status: 500, statusText: 'Error' });
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS]);
    fixture.detectChanges();

    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Datenbank nicht erreichbar.');
    expect(el.querySelector('.admin-mode-quick-access__table')).toBeFalsy();
  });
});
