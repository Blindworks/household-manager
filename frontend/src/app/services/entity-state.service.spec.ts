import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EntityStateService } from './entity-state.service';
import { EntityState } from '../models/entity-state.model';

describe('EntityStateService', () => {
  let service: EntityStateService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(EntityStateService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads all entities', () => {
    const entities: EntityState[] = [];
    service.getEntities().subscribe(result => expect(result).toEqual(entities));

    const req = httpMock.expectOne('/api/v1/entities');
    expect(req.request.method).toBe('GET');
    req.flush(entities);
  });

  it('passes domain and source as query params', () => {
    service.getEntities('SENSOR', 'ZIGBEE').subscribe();

    const req = httpMock.expectOne(r => r.url === '/api/v1/entities');
    expect(req.request.params.get('domain')).toBe('SENSOR');
    expect(req.request.params.get('source')).toBe('ZIGBEE');
    req.flush([]);
  });

  it('deletes an entity by id', () => {
    service.deleteEntity('sensor.zigbee_bad_temperature').subscribe();

    const req = httpMock.expectOne('/api/v1/entities/sensor.zigbee_bad_temperature');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
