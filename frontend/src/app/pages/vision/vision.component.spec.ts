import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { VisionComponent } from './vision.component';

describe('VisionComponent', () => {
  let fixture: ComponentFixture<VisionComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VisionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(VisionComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  function flushInitialRequests(loggedIn: boolean): void {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/vision/status').flush({
      sidecarReachable: true, loggedIn, cameraFound: true, cameraName: 'Haustuer', lastPollAt: null
    });
    httpMock.expectOne('/api/v1/vision/persons').flush([
      { id: 1, name: 'Benedikt', active: true, photoCount: 3 }
    ]);
    httpMock.expectOne(r => r.url.startsWith('/api/v1/vision/recognitions')).flush([]);
    fixture.detectChanges();
  }

  it('zeigt Personen mit Fotoanzahl', () => {
    flushInitialRequests(true);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Benedikt');
    expect(text).toContain('3');
  });

  it('zeigt das Login-Formular, wenn nicht angemeldet', () => {
    flushInitialRequests(false);
    const form = (fixture.nativeElement as HTMLElement).querySelector('.vision__login');
    expect(form).toBeTruthy();
  });
});
