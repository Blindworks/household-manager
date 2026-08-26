import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminPresenceComponent } from './admin-presence.component';
import { PresenceDeviceAdmin, PresenceStatusResponse } from '../../models/presence.model';
import { HouseholdUser } from '../../models/household-user.model';

const DEVICES_URL = '/api/v1/presence/devices';
const STATUS_URL = '/api/v1/presence/status';
const USERS_URL = '/api/v1/users';
const SETTINGS_URL = '/api/v1/presence/settings';
const REFRESH_URL = '/api/v1/presence/refresh';

const BENEDIKT: HouseholdUser = { id: 5, displayName: 'Benedikt', enabled: true };
const PARTNERIN: HouseholdUser = { id: 6, displayName: 'Partnerin', enabled: true };
/** Deaktiviertes Mitglied: bewusst mit einem Bestandsgeraet verknuepft (siehe Test B). */
const MITBEWOHNER_DISABLED: HouseholdUser = { id: 7, displayName: 'Mitbewohner', enabled: false };

const USERS: HouseholdUser[] = [BENEDIKT, PARTNERIN];
const USERS_WITH_DISABLED: HouseholdUser[] = [BENEDIKT, PARTNERIN, MITBEWOHNER_DISABLED];

const DEVICE_BENEDIKT: PresenceDeviceAdmin = {
  id: 1, userId: 5, name: 'iPhone Benedikt', host: '192.168.1.50', active: true
};
/** Geraet eines deaktivierten Mitglieds - fuer den "(deaktiviert)"-Test. */
const DEVICE_MITBEWOHNER: PresenceDeviceAdmin = {
  id: 2, userId: 7, name: 'iPhone Mitbewohner', host: '192.168.1.52', active: true
};

const SETTINGS_10 = { awayGraceMinutes: 10 };

const STATUS_EMPTY: PresenceStatusResponse = { householdState: 'unknown', persons: [] };

function statusWithLastSeen(deviceId: number, lastSeenAt: string): PresenceStatusResponse {
  return {
    householdState: 'on',
    persons: [{
      userId: 5, displayName: 'Benedikt', state: 'on', lastSeenAt,
      devices: [{ id: deviceId, name: 'iPhone Benedikt', active: true, lastSeenAt, lastCheckedAt: lastSeenAt }]
    }]
  };
}

describe('AdminPresenceComponent', () => {
  let fixture: ComponentFixture<AdminPresenceComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminPresenceComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminPresenceComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  /**
   * Startet die Seite und beantwortet alle vier Startabrufe.
   *
   * Das `whenStable()` ist nicht optional: Innerhalb eines `<form>` registriert NgForm
   * jedes NgModel erst in einem Microtask. Vorher haengt der ValueAccessor noch nicht am
   * Modell, und ein `input`-Ereignis aus dem Test veraendert das Formular nicht.
   */
  async function loadWith(
    devices: PresenceDeviceAdmin[],
    status: PresenceStatusResponse = STATUS_EMPTY,
    users: HouseholdUser[] = USERS,
    settings = SETTINGS_10
  ): Promise<void> {
    fixture.detectChanges();
    httpMock.expectOne(DEVICES_URL).flush(devices);
    httpMock.expectOne(STATUS_URL).flush(status);
    httpMock.expectOne(USERS_URL).flush(users);
    httpMock.expectOne(SETTINGS_URL).flush(settings);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  /**
   * Jede Aktion, die intern `load()` aufruft, holt Geraete UND "Zuletzt gesehen" erneut
   * (siehe `AdminPresenceComponent.load` - Absicht: die Diagnosespalte soll jede Aktion
   * auffrischen, nicht nur den ersten Seitenaufbau).
   */
  function flushReload(devices: PresenceDeviceAdmin[], status: PresenceStatusResponse = STATUS_EMPTY): void {
    httpMock.expectOne(DEVICES_URL).flush(devices);
    httpMock.expectOne(STATUS_URL).flush(status);
  }

  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-presence__table tbody tr'));
  }

  function setInput(name: string, value: string): void {
    const input = el.querySelector(`[name="${name}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  /** Waehlt eine Option im Personen-Select ueber ihren sichtbaren Text (echtes DOM-Ereignis). */
  function selectPerson(displayName: string): void {
    const select = el.querySelector('select[name="userId"]') as HTMLSelectElement;
    const index = Array.from(select.options).findIndex(option => option.textContent?.trim() === displayName);
    expect(index).toBeGreaterThan(-1);
    select.selectedIndex = index;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function submitButton(): HTMLButtonElement {
    return el.querySelector('.admin-presence__form button[type="submit"]') as HTMLButtonElement;
  }

  function editButtonOf(row: HTMLElement): HTMLButtonElement {
    return row.querySelector('button') as HTMLButtonElement;
  }

  function toggleButtonOf(row: HTMLElement): HTMLButtonElement {
    return row.querySelector('.admin-presence__toggle-active') as HTMLButtonElement;
  }

  function deleteButtonOf(row: HTMLElement): HTMLButtonElement {
    return row.querySelector('.admin-presence__delete') as HTMLButtonElement;
  }

  function refreshButton(): HTMLButtonElement {
    return el.querySelector('.admin-presence__refresh button') as HTMLButtonElement;
  }

  it('laedt Geraete, Personen und Karenzzeit und zeigt sie an', async () => {
    await loadWith([DEVICE_BENEDIKT], statusWithLastSeen(1, '2026-08-25T10:00:00'));

    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('Benedikt');
    expect(rows()[0].textContent).toContain('iPhone Benedikt');
    expect(rows()[0].textContent).toContain('192.168.1.50');
    expect(rows()[0].textContent).toContain('25.08.');
    expect(rows()[0].textContent).toContain('10:00');
    expect((el.querySelector('[name="graceMinutes"]') as HTMLInputElement).value).toBe('10');
  });

  it('zeigt "–" und laesst die Tabelle stehen, wenn der Status-Abruf fehlschlaegt', () => {
    fixture.detectChanges();
    httpMock.expectOne(DEVICES_URL).flush([DEVICE_BENEDIKT]);
    httpMock.expectOne(STATUS_URL).flush(null, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(USERS_URL).flush(USERS);
    httpMock.expectOne(SETTINGS_URL).flush(SETTINGS_10);
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__table')).toBeTruthy();
    expect(rows()[0].textContent).toContain('–');
    expect(el.querySelector('.admin-presence__error')).toBeFalsy();
  });

  it('zeigt einen eigenen Fehler fuer die Haushaltsmitglieder, den Geraete-Aktionen nicht loeschen', async () => {
    fixture.detectChanges();
    httpMock.expectOne(DEVICES_URL).flush([DEVICE_BENEDIKT]);
    httpMock.expectOne(STATUS_URL).flush(STATUS_EMPTY);
    httpMock.expectOne(USERS_URL).flush(null, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(SETTINGS_URL).flush(SETTINGS_10);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.querySelector('.admin-presence__field-error')?.textContent)
      .toContain('Die Haushaltsmitglieder konnten nicht geladen werden.');
    expect(el.querySelector('.admin-presence__error')).toBeFalsy();

    // Eine beliebige Geraete-Aktion setzt errorMessage zurueck - usersMessage darf das
    // nicht mit betreffen, sonst verschwindet die einzige Erklaerung fuer das leere Dropdown.
    editButtonOf(rows()[0]).click();
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__field-error')?.textContent)
      .toContain('Die Haushaltsmitglieder konnten nicht geladen werden.');
  });

  it('haelt eine deaktivierte Person sichtbar und beschriftet sie in Tabelle und Formular', async () => {
    await loadWith([DEVICE_MITBEWOHNER], STATUS_EMPTY, USERS_WITH_DISABLED);

    expect(rows()[0].textContent).toContain('Mitbewohner (deaktiviert)');

    editButtonOf(rows()[0]).click();
    fixture.detectChanges();
    await fixture.whenStable();

    const options = Array.from(
      (el.querySelector('select[name="userId"]') as HTMLSelectElement).options
    ).map(option => option.textContent?.trim());
    expect(options).toContain('Mitbewohner (deaktiviert)');
    expect(fixture.componentInstance.form.userId).toBe(7);

    submitButton().click();
    const update = httpMock.expectOne(`${DEVICES_URL}/2`);
    expect(update.request.body.userId).toBe(7);
    update.flush(DEVICE_MITBEWOHNER);
    flushReload([DEVICE_MITBEWOHNER]);
  });

  it('legt per Klick auf "Anlegen" ein neues Geraet mit den Formularwerten an', async () => {
    await loadWith([]);

    selectPerson('Partnerin');
    setInput('name', 'iPhone Partnerin');
    setInput('host', '192.168.1.51');
    submitButton().click();

    const created = httpMock.expectOne(DEVICES_URL);
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({
      userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true
    });
    created.flush({ id: 2, userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true });
    flushReload([{ id: 2, userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true }]);
  });

  it('lehnt einen leeren Namen per Klick auf "Anlegen" ohne Anfrage ab', async () => {
    await loadWith([]);

    selectPerson('Partnerin');
    setInput('host', '192.168.1.51');
    submitButton().click();
    fixture.detectChanges();

    httpMock.expectNone(DEVICES_URL);
    expect(el.querySelector('.admin-presence__error')?.textContent).toContain('Der Name darf nicht leer sein.');
  });

  it('lehnt einen leeren Host per Klick auf "Anlegen" ohne Anfrage ab', async () => {
    await loadWith([]);

    selectPerson('Partnerin');
    setInput('name', 'iPhone Partnerin');
    submitButton().click();
    fixture.detectChanges();

    httpMock.expectNone(DEVICES_URL);
    expect(el.querySelector('.admin-presence__error')?.textContent)
      .toContain('Die IP-Adresse darf nicht leer sein.');
  });

  it('lehnt eine leere Personenauswahl per Klick auf "Anlegen" ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('name', 'iPhone');
    setInput('host', '192.168.1.51');
    submitButton().click();
    fixture.detectChanges();

    httpMock.expectNone(DEVICES_URL);
    expect(el.querySelector('.admin-presence__error')?.textContent).toContain('Es ist keine Person ausgewählt.');
  });

  it('zeigt einen Serverfehler beim Anlegen im Banner an', async () => {
    await loadWith([]);

    selectPerson('Partnerin');
    setInput('name', 'iPhone Partnerin');
    setInput('host', '192.168.1.51');
    submitButton().click();

    httpMock.expectOne(DEVICES_URL)
      .flush({ message: 'Diese IP-Adresse wird bereits verwendet.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__error')?.textContent)
      .toContain('Diese IP-Adresse wird bereits verwendet.');
  });

  it('zeigt weiterhin die Fehlermeldung, wenn nach erfolgreichem Anlegen der Reload fehlschlaegt', async () => {
    await loadWith([]);

    selectPerson('Partnerin');
    setInput('name', 'iPhone Partnerin');
    setInput('host', '192.168.1.51');
    submitButton().click();

    httpMock.expectOne(DEVICES_URL)
      .flush({ id: 2, userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true });
    httpMock.expectOne(DEVICES_URL)
      .flush({ message: 'Geräte konnten nicht neu geladen werden.' }, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(STATUS_URL).flush(STATUS_EMPTY);
    fixture.detectChanges();
    // Das neu zugewiesene `form`-Objekt erreicht das NgModel-gebundene Input erst nach
    // einem weiteren Mikrotask (gleiche Ursache wie beim initialen Formular-Aufbau).
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__error')?.textContent)
      .toContain('Geräte konnten nicht neu geladen werden.');
    // Das Formular wurde trotzdem geleert (clearFormState lief), nur die Meldung blieb stehen.
    expect((el.querySelector('[name="name"]') as HTMLInputElement).value).toBe('');
  });

  it('schaltet den Aktiv-Status per Klick um und sendet die entgegengesetzte Richtung', async () => {
    await loadWith([DEVICE_BENEDIKT]);

    toggleButtonOf(rows()[0]).click();

    const update = httpMock.expectOne(`${DEVICES_URL}/1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      userId: 5, name: 'iPhone Benedikt', host: '192.168.1.50', active: false
    });
    update.flush({ ...DEVICE_BENEDIKT, active: false });
    flushReload([{ ...DEVICE_BENEDIKT, active: false }]);
  });

  it('sperrt den Aktiv-Knopf waehrend des laufenden Requests gegen Doppelklicks', async () => {
    await loadWith([DEVICE_BENEDIKT]);

    const button = toggleButtonOf(rows()[0]);
    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBeTrue();
    button.click(); // zweiter Klick waehrend die Sperre aktiv ist - darf keinen zweiten Request ausloesen

    const update = httpMock.expectOne(`${DEVICES_URL}/1`);
    update.flush({ ...DEVICE_BENEDIKT, active: false });
    flushReload([{ ...DEVICE_BENEDIKT, active: false }]);
  });

  it('aktualisiert "Zuletzt gesehen" bei jeder Aktion erneut', async () => {
    await loadWith([DEVICE_BENEDIKT], statusWithLastSeen(1, '2026-08-25T10:00:00'));
    expect(rows()[0].textContent).toContain('10:00');

    toggleButtonOf(rows()[0]).click();
    httpMock.expectOne(`${DEVICES_URL}/1`).flush({ ...DEVICE_BENEDIKT, active: false });
    flushReload([{ ...DEVICE_BENEDIKT, active: false }], statusWithLastSeen(1, '2026-08-25T11:30:00'));
    fixture.detectChanges();

    expect(rows()[0].textContent).toContain('11:30');
  });

  it('loescht ein Geraet nach Bestaetigung', async () => {
    await loadWith([DEVICE_BENEDIKT]);
    spyOn(window, 'confirm').and.returnValue(true);

    deleteButtonOf(rows()[0]).click();

    const deleted = httpMock.expectOne(`${DEVICES_URL}/1`);
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    flushReload([]);
  });

  it('loescht nicht ohne Bestaetigung', async () => {
    await loadWith([DEVICE_BENEDIKT]);
    spyOn(window, 'confirm').and.returnValue(false);

    deleteButtonOf(rows()[0]).click();

    httpMock.expectNone(`${DEVICES_URL}/1`);
    expect(window.confirm).toHaveBeenCalled();
  });

  it('blendet die Tabelle bei fehlgeschlagenem Laden aus und zeigt einen eigenen Fehlertext', () => {
    fixture.detectChanges();
    httpMock.expectOne(DEVICES_URL)
      .flush({ message: 'Datenbank nicht erreichbar.' }, { status: 500, statusText: 'Error' });
    httpMock.expectOne(STATUS_URL).flush(STATUS_EMPTY);
    httpMock.expectOne(USERS_URL).flush(USERS);
    httpMock.expectOne(SETTINGS_URL).flush(SETTINGS_10);
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__table')).toBeFalsy();
    expect(el.textContent).toContain('Die Geräte konnten nicht geladen werden.');
    expect(el.querySelector('.admin-presence__error')?.textContent).toContain('Datenbank nicht erreichbar.');
  });

  it('zeigt einen Ladehinweis, bevor die erste Antwort da ist', () => {
    fixture.detectChanges();

    expect(el.textContent).toContain('Wird geladen');
    expect(el.querySelector('.admin-presence__table')).toBeFalsy();

    httpMock.expectOne(DEVICES_URL).flush([]);
    httpMock.expectOne(STATUS_URL).flush(STATUS_EMPTY);
    httpMock.expectOne(USERS_URL).flush(USERS);
    httpMock.expectOne(SETTINGS_URL).flush(SETTINGS_10);
  });

  it('unterscheidet Erfolg und Fehler der Karenzzeit sichtbar', async () => {
    await loadWith([]);
    const settingsSubmit = el.querySelector('.admin-presence__settings-form button[type="submit"]') as HTMLButtonElement;

    setInput('graceMinutes', '15');
    settingsSubmit.click();
    httpMock.expectOne(SETTINGS_URL).flush({ awayGraceMinutes: 15 });
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__message--success')).toBeTruthy();
    expect(el.querySelector('.admin-presence__message--error')).toBeFalsy();

    setInput('graceMinutes', '20');
    settingsSubmit.click();
    httpMock.expectOne(SETTINGS_URL).flush({ message: 'Fehler.' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__message--error')).toBeTruthy();
    expect(el.querySelector('.admin-presence__message--success')).toBeFalsy();
  });

  it('lehnt eine Karenzzeit ausserhalb von 1 bis 1440 Minuten clientseitig ab', async () => {
    await loadWith([]);
    const settingsSubmit = el.querySelector('.admin-presence__settings-form button[type="submit"]') as HTMLButtonElement;

    setInput('graceMinutes', '1500');
    settingsSubmit.click();
    fixture.detectChanges();

    httpMock.expectNone(SETTINGS_URL);
    expect(el.querySelector('.admin-presence__message--error')?.textContent).toContain('1440');

    setInput('graceMinutes', '0');
    settingsSubmit.click();
    fixture.detectChanges();

    httpMock.expectNone(SETTINGS_URL);
    expect(el.querySelector('.admin-presence__message--error')).toBeTruthy();
  });

  it('stoesst per Klick auf "Jetzt pruefen" einen manuellen Abruf an und uebernimmt die frische "Zuletzt gesehen"-Spalte', async () => {
    await loadWith([DEVICE_BENEDIKT], statusWithLastSeen(1, '2026-08-25T10:00:00'));
    expect(rows()[0].textContent).toContain('10:00');

    refreshButton().click();

    const request = httpMock.expectOne(REFRESH_URL);
    expect(request.request.method).toBe('POST');
    request.flush(statusWithLastSeen(1, '2026-08-26T09:15:00'));
    fixture.detectChanges();

    expect(rows()[0].textContent).toContain('09:15');
    // Kein zweiter Geraete-/Status-Roundtrip: die Antwort traegt den frischen Status bereits.
    httpMock.expectNone(DEVICES_URL);
  });

  it('sperrt den "Jetzt pruefen"-Knopf waehrend des Requests und entsperrt ihn danach wieder - auch nach einem Fehler', async () => {
    await loadWith([DEVICE_BENEDIKT]);

    const button = refreshButton();
    expect(button.disabled).toBeFalse();

    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBeTrue();

    httpMock.expectOne(REFRESH_URL)
      .flush({ message: 'Fehler.' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(button.disabled).toBeFalse();

    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBeTrue();

    httpMock.expectOne(REFRESH_URL).flush(statusWithLastSeen(1, '2026-08-26T09:15:00'));
    fixture.detectChanges();

    expect(button.disabled).toBeFalse();
  });

  it('zeigt bei 429 eine eigene Meldung, die die Geraete-Fehlermeldung unberuehrt laesst', async () => {
    await loadWith([DEVICE_BENEDIKT]);

    refreshButton().click();
    httpMock.expectOne(REFRESH_URL)
      .flush({ message: 'Es laeuft bereits ein Abruf.' }, { status: 429, statusText: 'Too Many Requests' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__refresh .admin-presence__message--error')?.textContent)
      .toContain('Es laeuft bereits ein Abruf.');
    expect(el.querySelector('.admin-presence__error')).toBeFalsy();

    // Die eigentliche Trennung: eine NACHFOLGENDE Geraete-Aktion (die errorMessage
    // zuruecksetzt) darf die Abruf-Meldung nicht mit weglöschen. Ohne eigenes Signal
    // (Muster `usersMessage`) fiele eine Zusammenlegung mit errorMessage hier nicht auf.
    toggleButtonOf(rows()[0]).click();
    httpMock.expectOne(`${DEVICES_URL}/1`).flush({ ...DEVICE_BENEDIKT, active: false });
    flushReload([{ ...DEVICE_BENEDIKT, active: false }]);
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__refresh .admin-presence__message--error')?.textContent)
      .toContain('Es laeuft bereits ein Abruf.');
  });

  it('zeigt nach erfolgreichem manuellen Abruf eine Erfolgsmeldung', async () => {
    await loadWith([DEVICE_BENEDIKT]);

    refreshButton().click();
    httpMock.expectOne(REFRESH_URL).flush(statusWithLastSeen(1, '2026-08-26T09:15:00'));
    fixture.detectChanges();

    expect(el.querySelector('.admin-presence__refresh .admin-presence__message--success')?.textContent)
      .toContain('Geprüft.');
    expect(el.querySelector('.admin-presence__refresh .admin-presence__message--error')).toBeFalsy();
  });
});
