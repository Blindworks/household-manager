import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { AdminPresenceComponent } from './admin-presence.component';

// main.ts registriert die de-Locale nur fuer die App — Karma laedt main.ts nicht,
// ohne diese Zeile wirft die date-Pipe mit explizitem 'de' im Template.
registerLocaleData(localeDe);

describe('AdminPresenceComponent', () => {
  let fixture: ComponentFixture<AdminPresenceComponent>;
  let component: AdminPresenceComponent;
  let httpMock: HttpTestingController;

  const USERS = [
    { id: 5, displayName: 'Benedikt', enabled: true },
    { id: 6, displayName: 'Partnerin', enabled: true }
  ];
  const DEVICES = [
    { id: 1, userId: 5, name: 'iPhone Benedikt', host: '192.168.1.50', active: true }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminPresenceComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(AdminPresenceComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  const STATUS = {
    householdState: 'on',
    persons: [{
      userId: 5, displayName: 'Benedikt', state: 'on', lastSeenAt: '2026-08-25T10:00:00',
      devices: [{
        id: 1, name: 'iPhone Benedikt', active: true,
        lastSeenAt: '2026-08-25T10:00:00', lastCheckedAt: '2026-08-25T10:00:30'
      }]
    }]
  };

  function flushInitialRequests(): void {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/presence/devices').flush(DEVICES);
    httpMock.expectOne('/api/v1/presence/status').flush(STATUS);
    httpMock.expectOne('/api/v1/users').flush(USERS);
    httpMock.expectOne('/api/v1/presence/settings').flush({ awayGraceMinutes: 10 });
    fixture.detectChanges();
  }

  it('laedt Geraete, Personen und Karenzzeit beim Start', () => {
    flushInitialRequests();
    expect(component.devices().length).toBe(1);
    expect(component.users().length).toBe(2);
    expect(component.graceMinutes).toBe(10);
  });

  it('zeigt den Zuletzt-gesehen-Zeitpunkt aus dem Status zum passenden Geraet', () => {
    flushInitialRequests();
    expect(component.lastSeenOf(DEVICES[0])).toBe('2026-08-25T10:00:00');
  });

  it('laesst die Geraeteliste stehen, wenn der Status-Abruf fehlschlaegt', () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/presence/devices').flush(DEVICES);
    httpMock.expectOne('/api/v1/presence/status')
      .flush(null, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne('/api/v1/users').flush(USERS);
    httpMock.expectOne('/api/v1/presence/settings').flush({ awayGraceMinutes: 10 });
    fixture.detectChanges();

    expect(component.devices().length).toBe(1);
    expect(component.lastSeenOf(DEVICES[0])).toBeNull();
    expect(component.errorMessage()).toBeNull();
  });

  it('legt ein Geraet mit Person an', () => {
    flushInitialRequests();
    component.form = { id: null, userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true };
    component.save();

    const request = httpMock.expectOne('/api/v1/presence/devices');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.userId).toBe(6);
    request.flush({ id: 2, userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true });
    httpMock.expectOne('/api/v1/presence/devices').flush(DEVICES);
  });

  it('sendet beim Aktiv-Toggle immer den kompletten Request', () => {
    flushInitialRequests();
    component.setActive(DEVICES[0], false);

    const request = httpMock.expectOne('/api/v1/presence/devices/1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      userId: 5, name: 'iPhone Benedikt', host: '192.168.1.50', active: false
    });
    request.flush({ ...DEVICES[0], active: false });
    httpMock.expectOne('/api/v1/presence/devices').flush(DEVICES);
  });

  it('speichert die Karenzzeit', () => {
    flushInitialRequests();
    component.graceMinutes = 15;
    component.saveSettings();

    const request = httpMock.expectOne('/api/v1/presence/settings');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ awayGraceMinutes: 15 });
    request.flush({ awayGraceMinutes: 15 });
  });

  it('lehnt eine leere Personenauswahl clientseitig ab', () => {
    flushInitialRequests();
    component.form = { id: null, userId: null, name: 'iPhone', host: '192.168.1.51', active: true };
    component.save();
    expect(component.errorMessage()).toBeTruthy();
    httpMock.expectNone('/api/v1/presence/devices');
  });
});
