import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { PetFoodComponent } from './pet-food.component';

// main.ts registriert die de-Locale nur fuer die App — Karma laedt main.ts nicht,
// ohne diese Zeile wirft die number-Pipe mit explizitem 'de' im Test.
registerLocaleData(localeDe);

describe('PetFoodComponent', () => {
  let fixture: ComponentFixture<PetFoodComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PetFoodComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(PetFoodComponent);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  function flushInitialRequests(status = {
    cansRemaining: 12.5, targetCans: 48, percent: 26, daysRemaining: 12
  }): void {
    httpMock.expectOne('/api/v1/pet-food').flush(status);
    httpMock.expectOne(r => r.url === '/api/v1/pet-food/transactions').flush([]);
    fixture.detectChanges();
  }

  it('zeigt Bestand, Prozent und Reichweite an', () => {
    flushInitialRequests();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('12,5');
    expect(text).toContain('48');
    expect(text).toContain('26');
    expect(text).toContain('12');
  });

  it('Farblogik: gruen, gelb unter 25 %, rot unter der Warnschwelle von 7 Dosen', () => {
    flushInitialRequests();
    const component = fixture.componentInstance;
    expect(component.fillTone({ cansRemaining: 30, targetCans: 48, percent: 63, daysRemaining: 30 }))
      .toBe('ok');
    expect(component.fillTone({ cansRemaining: 10, targetCans: 48, percent: 21, daysRemaining: 10 }))
      .toBe('warn');
    expect(component.fillTone({ cansRemaining: 6.5, targetCans: 48, percent: 14, daysRemaining: 6 }))
      .toBe('critical');
  });
});
