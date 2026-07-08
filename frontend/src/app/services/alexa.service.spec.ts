import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AlexaService } from './alexa.service';

describe('AlexaService', () => {
  let service: AlexaService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AlexaService]
    });
    service = TestBed.inject(AlexaService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sendet Durchsage per POST an /announce', () => {
    service.announce({ text: 'Hallo', serialNumbers: ['DSN1'], mode: 'ANNOUNCE' })
      .subscribe();
    const req = httpMock.expectOne('/api/v1/alexa/announce');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.text).toBe('Hallo');
    req.flush(null);
  });

  it('laedt Geraete mit rescan-Flag', () => {
    service.getDevices(true).subscribe();
    const req = httpMock.expectOne('/api/v1/alexa/devices?rescan=true');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('fragt Login-Status ab', () => {
    service.getAuthStatus().subscribe();
    const req = httpMock.expectOne('/api/v1/alexa/auth/status');
    expect(req.request.method).toBe('GET');
    req.flush({ loggedIn: false, reauthRequired: false });
  });
});
