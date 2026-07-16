import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SwitchService } from './switch.service';
import { SwitchEntity } from '../models/switch.model';

describe('SwitchService', () => {
  let service: SwitchService;
  let httpMock: HttpTestingController;

  const entity: SwitchEntity = {
    entityId: 'switch.kasa_abc',
    domain: 'SWITCH',
    source: 'KASA',
    displayName: 'Stehlampe',
    state: 'on',
    available: true,
    icon: 'toggle_on',
    toggleCount: 3,
    lastToggledAt: '2026-07-15T20:00:00'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(SwitchService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt alle Schalter ohne Limit', () => {
    service.getSwitches().subscribe(result => expect(result).toEqual([entity]));

    const req = httpMock.expectOne('/api/v1/switches');
    expect(req.request.method).toBe('GET');
    req.flush([entity]);
  });

  it('reicht das Limit als Query-Parameter durch', () => {
    service.getSwitches(4).subscribe();

    const req = httpMock.expectOne(r => r.url === '/api/v1/switches');
    expect(req.request.params.get('limit')).toBe('4');
    req.flush([]);
  });

  it('schaltet einen Schalter um', () => {
    service.toggle('switch.kasa_abc').subscribe(result => expect(result).toEqual(entity));

    const req = httpMock.expectOne('/api/v1/switches/switch.kasa_abc/toggle');
    expect(req.request.method).toBe('POST');
    req.flush(entity);
  });

  it('meldet einen Fehler als Error weiter', () => {
    let failed = false;
    service.toggle('switch.kasa_abc').subscribe({ error: () => (failed = true) });

    httpMock.expectOne('/api/v1/switches/switch.kasa_abc/toggle')
      .flush('Geraet nicht erreichbar', { status: 502, statusText: 'Bad Gateway' });

    expect(failed).toBeTrue();
  });
});
