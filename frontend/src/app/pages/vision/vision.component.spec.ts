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

  it('benennt eine Person ueber die Inline-Bearbeitung um', () => {
    flushInitialRequests(true);
    const el = fixture.nativeElement as HTMLElement;

    (el.querySelector('.vision__rename') as HTMLButtonElement).click();
    fixture.detectChanges();
    const input = el.querySelector('.vision__rename-input') as HTMLInputElement;
    expect(input).toBeTruthy();
    input.value = 'Benedikt L.';
    input.dispatchEvent(new Event('input'));
    (el.querySelector('.vision__rename-save') as HTMLButtonElement).click();

    const update = httpMock.expectOne('/api/v1/vision/persons/1');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({ name: 'Benedikt L.', active: true });
    update.flush({ id: 1, name: 'Benedikt L.', active: true, photoCount: 3 });
    httpMock.expectOne('/api/v1/vision/persons')
      .flush([{ id: 1, name: 'Benedikt L.', active: true, photoCount: 3 }]);
    fixture.detectChanges();

    expect(el.textContent).toContain('Benedikt L.');
    expect(el.querySelector('.vision__rename-input')).toBeFalsy();
  });

  it('schaltet eine Person auf inaktiv und markiert sie in der Liste', () => {
    flushInitialRequests(true);
    const el = fixture.nativeElement as HTMLElement;

    (el.querySelector('.vision__toggle-active') as HTMLButtonElement).click();

    const update = httpMock.expectOne('/api/v1/vision/persons/1');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({ name: 'Benedikt', active: false });
    update.flush({ id: 1, name: 'Benedikt', active: false, photoCount: 3 });
    httpMock.expectOne('/api/v1/vision/persons')
      .flush([{ id: 1, name: 'Benedikt', active: false, photoCount: 3 }]);
    fixture.detectChanges();

    expect(el.querySelector('.vision__person--inactive')).toBeTruthy();
    expect(el.textContent).toContain('inaktiv');
    expect(el.querySelector('.vision__toggle-active')?.textContent?.trim()).toBe('Aktivieren');
  });

  it('zeigt eine Fehlermeldung, wenn das Anlegen einer Person fehlschlaegt', () => {
    flushInitialRequests(true);
    const el = fixture.nativeElement as HTMLElement;
    const nameInput = el.querySelector('.vision__new-person input') as HTMLInputElement;
    nameInput.value = 'Benedikt';
    nameInput.dispatchEvent(new Event('input'));

    (el.querySelector('.vision__new-person button') as HTMLButtonElement).click();
    httpMock.expectOne('/api/v1/vision/persons')
      .flush({ message: 'Name ist bereits vergeben.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el.querySelector('.vision__error')?.textContent).toContain('Name ist bereits vergeben.');
  });

  it('meldet einen fehlgeschlagenen Personen-Ladevorgang im Abschnitt statt im Aktions-Banner', () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/vision/status').flush({
      sidecarReachable: true, loggedIn: true, cameraFound: true, cameraName: 'Haustuer', lastPollAt: null
    });
    httpMock.expectOne('/api/v1/vision/persons')
      .flush({ message: 'Personen konnten nicht geladen werden.' },
             { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(r => r.url.startsWith('/api/v1/vision/recognitions')).flush([]);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Personen konnten nicht geladen werden.');
    expect(el.querySelector('.vision__error')).toBeFalsy();
  });
});
