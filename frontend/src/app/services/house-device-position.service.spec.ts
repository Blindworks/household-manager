import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { HouseDevicePositionService } from './house-device-position.service';

describe('HouseDevicePositionService', () => {
  let service: HouseDevicePositionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(HouseDevicePositionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list: GET auf die Basis-URL', () => {
    let result: unknown;
    service.list().subscribe(r => (result = r));
    const req = http.expectOne('/api/v1/house/device-positions');
    expect(req.request.method).toBe('GET');
    req.flush([{ deviceId: 'a', x: 1, y: 2 }]);
    expect(result).toEqual([{ deviceId: 'a', x: 1, y: 2 }]);
  });

  it('set: PUT mit {x, y} und kodierter Geraete-Id', () => {
    service.set('a/b c', { x: 10, y: 20 }).subscribe();
    const req = http.expectOne('/api/v1/house/device-positions/a%2Fb%20c');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ x: 10, y: 20 });
    req.flush({ deviceId: 'a/b c', x: 10, y: 20 });
  });

  it('remove: DELETE auf die Geraete-Id', () => {
    service.remove('a/b c').subscribe();
    const req = http.expectOne('/api/v1/house/device-positions/a%2Fb%20c');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
