import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { HouseOpening, HouseOpeningRequest } from '../models/house-opening.model';
import { HouseOpeningService } from './house-opening.service';

const REQUEST: HouseOpeningRequest = {
  floor: 'ground', kind: 'WINDOW', size: 'NORMAL', x1: 20, y1: 130, x2: 20, y2: 170,
  hinge: 'START', swing: 'POSITIVE', deviceId: null
};
const OPENING: HouseOpening = { id: 7, ...REQUEST };

describe('HouseOpeningService', () => {
  let service: HouseOpeningService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(HouseOpeningService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list: GET auf die Basis-URL', () => {
    let result: unknown;
    service.list().subscribe(r => (result = r));
    const req = http.expectOne('/api/v1/house/openings');
    expect(req.request.method).toBe('GET');
    req.flush([OPENING]);
    expect(result).toEqual([OPENING]);
  });

  it('create: POST mit dem Request ohne id', () => {
    let result: unknown;
    service.create(REQUEST).subscribe(r => (result = r));
    const req = http.expectOne('/api/v1/house/openings');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(REQUEST);
    req.flush(OPENING);
    expect(result).toEqual(OPENING);
  });

  it('update: PUT auf die id mit dem Request', () => {
    service.update(7, REQUEST).subscribe();
    const req = http.expectOne('/api/v1/house/openings/7');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(REQUEST);
    req.flush(OPENING);
  });

  it('remove: DELETE auf die id', () => {
    service.remove(7).subscribe();
    const req = http.expectOne('/api/v1/house/openings/7');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
