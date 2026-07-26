import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { CurrentUser } from '../models/auth.model';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  const user: CurrentUser = {
    username: 'bene', displayName: 'Benedikt', role: 'ADMIN', mustChangePassword: false
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt den aktuellen Nutzer und cached ihn', () => {
    service.ensureLoaded().subscribe(result => expect(result).toEqual(user));
    httpMock.expectOne('/api/v1/auth/me').flush(user);

    // zweiter Aufruf geht nicht mehr ans Netz
    service.ensureLoaded().subscribe(result => expect(result).toEqual(user));
    expect(service.currentUser()).toEqual(user);
    expect(service.isAdmin()).toBeTrue();
  });

  it('behandelt 401 als nicht angemeldet', () => {
    let result: CurrentUser | null | undefined;
    service.ensureLoaded().subscribe(r => (result = r));
    httpMock.expectOne('/api/v1/auth/me').flush('nein', { status: 401, statusText: 'Unauthorized' });
    expect(result).toBeNull();
    expect(service.currentUser()).toBeNull();
  });

  it('setzt den Nutzer nach Login', () => {
    service.login({ username: 'bene', password: 'pw' }).subscribe();
    const req = httpMock.expectOne('/api/v1/auth/login');
    expect(req.request.method).toBe('POST');
    req.flush(user);
    expect(service.currentUser()).toEqual(user);
  });

  it('meldet 401 beim Login als verstaendlichen Fehler', () => {
    let message = '';
    service.login({ username: 'bene', password: 'falsch' })
      .subscribe({ error: (e: Error) => (message = e.message) });
    httpMock.expectOne('/api/v1/auth/login')
      .flush('nein', { status: 401, statusText: 'Unauthorized' });
    expect(message).toBe('Benutzername oder Passwort falsch.');
  });

  it('uebernimmt den Nutzer aus der Passwortwechsel-Antwort', () => {
    const forced: CurrentUser = { ...user, username: 'admin', mustChangePassword: true };
    service.login({ username: 'admin', password: 'changeit' }).subscribe();
    httpMock.expectOne('/api/v1/auth/login').flush(forced);
    expect(service.currentUser()?.mustChangePassword).toBeTrue();

    service.changePassword('changeit', 'neuesPasswort1').subscribe();
    const req = httpMock.expectOne('/api/v1/auth/password');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ currentPassword: 'changeit', newPassword: 'neuesPasswort1' });
    req.flush({ ...forced, mustChangePassword: false });
    expect(service.currentUser()?.mustChangePassword).toBeFalse();
  });

  it('meldet ein falsches aktuelles Passwort verstaendlich', () => {
    let message = '';
    service.changePassword('falsch', 'neuesPasswort1')
      .subscribe({ error: (e: Error) => (message = e.message) });
    httpMock.expectOne('/api/v1/auth/password').flush(
      { message: 'Aktuelles Passwort ist falsch.' }, { status: 400, statusText: 'Bad Request' });
    expect(message).toBe('Aktuelles Passwort ist falsch.');
  });

  it('leert den Nutzer nach Logout', () => {
    service.login({ username: 'bene', password: 'pw' }).subscribe();
    httpMock.expectOne('/api/v1/auth/login').flush(user);

    service.logout().subscribe();
    httpMock.expectOne('/api/v1/auth/logout').flush(null);
    expect(service.currentUser()).toBeNull();
  });
});
