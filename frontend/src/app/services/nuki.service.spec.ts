import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NukiService } from './nuki.service';
import { NukiLock } from '../models/nuki.model';

describe('NukiService', () => {
  let service: NukiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(NukiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt die Schloesser', () => {
    const locks: NukiLock[] = [
      { smartlockId: 1, name: 'Haustür', state: 'locked', doorState: 'off', batteryCharge: 85, batteryCritical: false }
    ];

    service.getLocks().subscribe(result => expect(result).toEqual(locks));

    const req = httpMock.expectOne('/api/v1/nuki/locks');
    expect(req.request.method).toBe('GET');
    req.flush(locks);
  });

  it('sendet eine Aktion', () => {
    service.sendAction(1, 'LOCK').subscribe();

    const req = httpMock.expectOne('/api/v1/nuki/locks/1/actions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ action: 'LOCK' });
    req.flush(null);
  });
});
