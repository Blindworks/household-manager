import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ModeService } from './mode.service';
import { ModeEntity } from '../models/mode.model';

describe('ModeService', () => {
  let service: ModeService;
  let httpMock: HttpTestingController;

  const mode: ModeEntity = {
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(ModeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt die Haus-Modi', () => {
    service.getModes().subscribe(result => expect(result).toEqual([mode]));

    const req = httpMock.expectOne('/api/v1/modes');
    expect(req.request.method).toBe('GET');
    req.flush([mode]);
  });

  it('schaltet einen Modus um', () => {
    service.toggle(mode.entityId).subscribe(result => expect(result.state).toBe('on'));

    const req = httpMock.expectOne('/api/v1/modes/input_boolean.manual_nachtmodus/toggle');
    expect(req.request.method).toBe('POST');
    req.flush({ ...mode, state: 'on' });
  });

  it('meldet einen Fehler als Error weiter', () => {
    let failed = false;
    service.toggle(mode.entityId).subscribe({ error: () => (failed = true) });

    httpMock.expectOne('/api/v1/modes/input_boolean.manual_nachtmodus/toggle')
      .flush('kaputt', { status: 500, statusText: 'Server Error' });

    expect(failed).toBeTrue();
  });
});
