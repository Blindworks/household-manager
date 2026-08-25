import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminNetworkDevicesComponent } from './admin-network-devices.component';
import { NetworkDeviceAdminResponse } from '../../models/network.model';

const DEVICES_URL = '/api/v1/network/devices';

const ROUTER: NetworkDeviceAdminResponse = {
  id: 1, name: 'Router', host: '192.168.1.1', tcpPort: null, sortOrder: 1, active: true
};
/** Bewusst inaktiv: das stille Reaktivieren beim Speichern ist der gefaehrliche Fall. */
const DRUCKER: NetworkDeviceAdminResponse = {
  id: 2, name: 'Drucker', host: '192.168.1.50', tcpPort: 9100, sortOrder: 2, active: false
};

describe('AdminNetworkDevicesComponent', () => {
  let fixture: ComponentFixture<AdminNetworkDevicesComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminNetworkDevicesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminNetworkDevicesComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  /**
   * Startet die Seite und beantwortet den Abruf der Liste.
   *
   * Das `whenStable()` ist nicht optional: Innerhalb eines `<form>` registriert NgForm
   * jedes NgModel erst in einem Microtask. Vorher haengt der ValueAccessor noch nicht am
   * Modell, und ein `input`-Ereignis aus dem Test veraendert das Formular nicht.
   */
  async function loadWith(devices: NetworkDeviceAdminResponse[]): Promise<void> {
    fixture.detectChanges();
    httpMock.expectOne(DEVICES_URL).flush(devices);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-network-devices__table tbody tr'));
  }

  function setInput(name: string, value: string): void {
    const input = el.querySelector(`[name="${name}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('zeigt die Geraete der Liste', async () => {
    await loadWith([ROUTER, DRUCKER]);

    expect(el.textContent).toContain('Router');
    expect(el.textContent).toContain('192.168.1.1');
    expect(el.textContent).toContain('Drucker');
    expect(el.textContent).toContain('9100');
  });

  it('legt ein neues Geraet mit den Formularwerten an', async () => {
    await loadWith([]);

    setInput('name', 'Switch');
    setInput('host', '192.168.1.5');
    setInput('tcpPort', '9999');

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const created = httpMock.expectOne(DEVICES_URL);
    expect(created.request.method).toBe('POST');
    // Kein Geraet vorhanden -> die Reihenfolge wird beim Laden automatisch vorgeschlagen
    // (Muster Kalender-Kategorien), das Feld bleibt unangetastet.
    expect(created.request.body).toEqual({
      name: 'Switch', host: '192.168.1.5', tcpPort: 9999, sortOrder: 10, active: true
    });
    created.flush({ id: 3, name: 'Switch', host: '192.168.1.5', tcpPort: 9999, sortOrder: 10, active: true });
    httpMock.expectOne(DEVICES_URL).flush([]);
  });

  it('lehnt einen leeren Namen ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('host', '192.168.1.5');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(DEVICES_URL);
    expect(el.querySelector('.admin-network-devices__error')?.textContent)
      .toContain('Der Name darf nicht leer sein.');
  });

  it('lehnt einen leeren Host ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('name', 'Switch');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(DEVICES_URL);
    expect(el.querySelector('.admin-network-devices__error')?.textContent)
      .toContain('Der Host darf nicht leer sein.');
  });

  it('uebernimmt ein Geraet zum Bearbeiten und ruft updateDevice', async () => {
    await loadWith([ROUTER]);

    (rows()[0].querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();

    const name = el.querySelector('[name="name"]') as HTMLInputElement;
    expect(name.value).toBe('Router');

    setInput('name', 'Router neu');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const update = httpMock.expectOne(`${DEVICES_URL}/1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      name: 'Router neu', host: '192.168.1.1', tcpPort: null, sortOrder: 1, active: true
    });
    update.flush({ ...ROUTER, name: 'Router neu' });
    httpMock.expectOne(DEVICES_URL).flush([{ ...ROUTER, name: 'Router neu' }]);
  });

  it('loescht ein Geraet nach Bestaetigung', async () => {
    await loadWith([ROUTER]);
    spyOn(window, 'confirm').and.returnValue(true);

    (rows()[0].querySelector('.admin-network-devices__delete') as HTMLButtonElement).click();

    const deleted = httpMock.expectOne(`${DEVICES_URL}/1`);
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    httpMock.expectOne(DEVICES_URL).flush([]);
  });

  it('loescht nicht ohne Bestaetigung', async () => {
    await loadWith([ROUTER]);
    spyOn(window, 'confirm').and.returnValue(false);

    (rows()[0].querySelector('.admin-network-devices__delete') as HTMLButtonElement).click();

    httpMock.expectNone(`${DEVICES_URL}/1`);
    expect(window.confirm).toHaveBeenCalled();
  });

  it('schaltet ein Geraet mit vollstaendigem Request aktiv/inaktiv', async () => {
    await loadWith([ROUTER]);

    (rows()[0].querySelector('.admin-network-devices__toggle-active') as HTMLButtonElement).click();

    const update = httpMock.expectOne(`${DEVICES_URL}/1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      name: 'Router', host: '192.168.1.1', tcpPort: null, sortOrder: 1, active: false
    });
    update.flush({ ...ROUTER, active: false });
    httpMock.expectOne(DEVICES_URL).flush([{ ...ROUTER, active: false }]);
  });

  it('zeigt einen Serverfehler beim Anlegen an', async () => {
    await loadWith([]);

    setInput('name', 'Switch');
    setInput('host', '192.168.1.5');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    httpMock.expectOne(DEVICES_URL)
      .flush({ message: 'Der Port muss zwischen 1 und 65535 liegen.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-network-devices__error')?.textContent)
      .toContain('Der Port muss zwischen 1 und 65535 liegen.');
  });

  it('zeigt einen Fehler, wenn das Laden der Liste fehlschlaegt', () => {
    fixture.detectChanges();
    httpMock.expectOne(DEVICES_URL)
      .flush({ message: 'Datenbank nicht erreichbar.' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-network-devices__error')?.textContent)
      .toContain('Datenbank nicht erreichbar.');
    expect(el.querySelector('.admin-network-devices__table')).toBeFalsy();
  });
});
