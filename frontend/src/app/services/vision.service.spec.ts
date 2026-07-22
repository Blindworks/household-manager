import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { VisionService } from './vision.service';

describe('VisionService', () => {
  let service: VisionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(VisionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt Personen', () => {
    service.getPersons().subscribe(persons => {
      expect(persons.length).toBe(1);
      expect(persons[0].name).toBe('Benedikt');
    });
    const req = httpMock.expectOne('/api/v1/vision/persons');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 1, name: 'Benedikt', active: true, photoCount: 2 }]);
  });

  it('laedt ein Foto als multipart hoch', () => {
    const file = new File([new Uint8Array([1, 2, 3])], 'foto.jpg', { type: 'image/jpeg' });
    service.uploadPhoto(1, file).subscribe();
    const req = httpMock.expectOne('/api/v1/vision/persons/1/photos');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    req.flush({ id: 5, personId: 1, photoBase64: '' });
  });

  it('meldet Login-Daten an das Backend', () => {
    service.login('mail@example.com', 'geheim').subscribe();
    const req = httpMock.expectOne('/api/v1/vision/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'mail@example.com', password: 'geheim' });
    req.flush(null);
  });
});
