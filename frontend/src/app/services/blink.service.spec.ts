import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BlinkService } from './blink.service';

describe('BlinkService', () => {
  let service: BlinkService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(BlinkService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt die Kameraliste', () => {
    service.getCameras().subscribe();

    const req = httpMock.expectOne('/api/v1/blink/cameras');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('schaltet eine Kamera scharf', () => {
    service.setCameraArmed('cam1', true).subscribe();

    const req = httpMock.expectOne('/api/v1/blink/cameras/cam1/arm');
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });

  it('schaltet eine Kamera unscharf', () => {
    service.setCameraArmed('cam1', false).subscribe();

    const req = httpMock.expectOne('/api/v1/blink/cameras/cam1/disarm');
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });

  it('schaltet das Sync-Modul scharf/unscharf und encodiert den Namen', () => {
    service.setSystemArmed('Büro Süd', true).subscribe();
    const armReq = httpMock.expectOne(`/api/v1/blink/system/${encodeURIComponent('Büro Süd')}/arm`);
    expect(armReq.request.method).toBe('POST');
    armReq.flush(null);

    service.setSystemArmed('Büro Süd', false).subscribe();
    const disarmReq = httpMock.expectOne(`/api/v1/blink/system/${encodeURIComponent('Büro Süd')}/disarm`);
    expect(disarmReq.request.method).toBe('POST');
    disarmReq.flush(null);
  });

  it('baut die Thumbnail-URL mit Cache-Buster', () => {
    expect(service.thumbnailUrl('cam1', 12345)).toBe('/api/v1/blink/cameras/cam1/thumbnail?t=12345');
  });
});
