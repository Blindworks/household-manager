import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { WasteCollectionService } from './waste-collection.service';
import { WasteCollectionSettings } from '../models/waste-collection.model';

describe('WasteCollectionService', () => {
  let service: WasteCollectionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [WasteCollectionService]
    });
    service = TestBed.inject(WasteCollectionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fragt Termine ohne days-Parameter ab', () => {
    service.getUpcoming().subscribe();
    httpMock.expectOne('/api/v1/waste-collection/upcoming').flush([]);
  });

  it('reicht days als Query-Parameter durch', () => {
    service.getUpcoming(60).subscribe();
    httpMock.expectOne('/api/v1/waste-collection/upcoming?days=60').flush([]);
  });

  it('laedt die Settings', () => {
    service.getSettings().subscribe();
    const req = httpMock.expectOne('/api/v1/waste-collection/settings');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('speichert Settings per PUT', () => {
    const settings: WasteCollectionSettings = {
      enabled: true,
      icsUrl: 'https://x/cal.ics',
      lookaheadDays: 3,
      reminderEnabled: true,
      reminderTime: '19:00',
      reminderAlexaSerials: ['DSN1']
    };

    service.updateSettings(settings).subscribe();

    const req = httpMock.expectOne('/api/v1/waste-collection/settings');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(settings);
    req.flush(settings);
  });

  it('laedt den Abruf-Status', () => {
    service.getPollingStatus().subscribe();
    httpMock.expectOne('/api/v1/admin/waste-polling').flush({});
  });

  it('loest den Abruf per POST aus', () => {
    service.triggerPoll().subscribe();
    const req = httpMock.expectOne('/api/v1/admin/waste-polling/trigger');
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });

  it('reicht die Fehlermeldung des Backends durch', (done) => {
    service.updateSettings({} as WasteCollectionSettings).subscribe({
      error: (err: Error) => {
        expect(err.message).toBe('Die Uhrzeit muss im Format HH:mm angegeben werden.');
        done();
      }
    });

    httpMock.expectOne('/api/v1/waste-collection/settings').flush(
      { message: 'Die Uhrzeit muss im Format HH:mm angegeben werden.' },
      { status: 400, statusText: 'Bad Request' }
    );
  });
});
