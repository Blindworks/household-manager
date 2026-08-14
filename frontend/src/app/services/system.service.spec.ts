import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SystemService } from './system.service';

describe('SystemService', () => {
  let service: SystemService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(SystemService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loest den Reboot aus', () => {
    let completed = false;
    service.reboot().subscribe({ complete: () => (completed = true) });

    const req = httpMock.expectOne('/api/v1/system/reboot');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 202, statusText: 'Accepted' });

    expect(completed).toBeTrue();
  });

  it('reicht die Backend-Fehlermeldung als Error weiter', () => {
    let message = '';
    service.reboot().subscribe({ error: (err: Error) => (message = err.message) });

    httpMock.expectOne('/api/v1/system/reboot').flush(
      { message: 'Reboot ist nicht konfiguriert.' },
      { status: 400, statusText: 'Bad Request' }
    );

    expect(message).toBe('Reboot ist nicht konfiguriert.');
  });

  it('prueft die Erreichbarkeit ueber den Health-Endpunkt', () => {
    service.ping().subscribe();

    const req = httpMock.expectOne('/api/v1/health');
    expect(req.request.method).toBe('GET');
    req.flush({ status: 'UP' });
  });
});
