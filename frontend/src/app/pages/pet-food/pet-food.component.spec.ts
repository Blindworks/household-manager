import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { PetFoodComponent } from './pet-food.component';
import { PetSupply } from '../../models/pet-supply.model';

// main.ts registriert die de-Locale nur fuer die App — Karma laedt main.ts nicht,
// ohne diese Zeile wirft die number-Pipe mit explizitem 'de' im Test.
registerLocaleData(localeDe);

const FOOD: PetSupply = {
  key: 'toni_cans', name: 'Futtervorrat', unit: 'Dosen',
  amountRemaining: 12.5, targetAmount: 48, step: 0.5, perDay: 1,
  percent: 26, daysRemaining: 12
};

const TABLETS: PetSupply = {
  key: 'toni_vomisan', name: 'VomiSan-Tabletten', unit: 'Tabletten',
  amountRemaining: 30, targetAmount: 60, step: 1, perDay: 2,
  percent: 50, daysRemaining: 15
};

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

  function flushInitialRequests(supplies: PetSupply[] = [FOOD, TABLETS]): void {
    httpMock.expectOne('/api/v1/pet-supplies').flush(supplies);
    supplies.forEach(supply => {
      httpMock.expectOne(r => r.url === `/api/v1/pet-supplies/${supply.key}/transactions`).flush([]);
    });
    fixture.detectChanges();
  }

  it('zeigt eine Karte je Vorrat mit Bestand, Prozent und Reichweite', () => {
    flushInitialRequests();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelectorAll('.pet-food__supply').length).toBe(2);

    const text = element.textContent ?? '';
    expect(text).toContain('Futtervorrat');
    expect(text).toContain('12,5');
    expect(text).toContain('Dosen');
    expect(text).toContain('VomiSan-Tabletten');
    expect(text).toContain('Tabletten');
  });

  it('uebernimmt das Eingaberaster des jeweiligen Vorrats', () => {
    flushInitialRequests();
    const element = fixture.nativeElement as HTMLElement;
    const sections = element.querySelectorAll('.pet-food__supply');

    // Bewusst ueber die Sektion statt ueber den name-Selektor: [name] bindet
    // an den gleichnamigen Input der NgModel-Direktive, nicht ans DOM-Attribut
    // — im Markup ist der Name deshalb gar nicht als Attribut zu finden.
    const steps = Array.from(sections).map(section =>
      section.querySelector<HTMLInputElement>('input[type="number"]')?.getAttribute('step'));

    // Halbe Dosen sind erlaubt, halbe Tabletten nicht — das Raster kommt vom
    // Server und darf im Frontend nicht hart kodiert sein.
    expect(steps).toEqual(['0.5', '1']);
  });

  it('faerbt nach der Reichweite, nicht nach der Stueckzahl', () => {
    flushInitialRequests();
    const component = fixture.componentInstance;

    // 10 Tabletten sind mehr Stueck als die alte Dosenschwelle von 7,
    // reichen bei 2 pro Tag aber nur 5 Tage — also kritisch.
    expect(component.fillTone({ ...TABLETS, amountRemaining: 10, percent: 17, daysRemaining: 5 }))
      .toBe('critical');
    expect(component.fillTone({ ...FOOD, amountRemaining: 30, percent: 63, daysRemaining: 30 }))
      .toBe('ok');
    expect(component.fillTone({ ...FOOD, amountRemaining: 10, percent: 21, daysRemaining: 10 }))
      .toBe('warn');
  });

  it('haelt Fehler je Vorrat auseinander', () => {
    flushInitialRequests();
    const component = fixture.componentInstance;
    component.forms['toni_cans'].purchaseAmount = 6;
    component.submitPurchase(FOOD);

    httpMock.expectOne('/api/v1/pet-supplies/toni_cans/purchases')
      .flush({ message: 'Kaputt' }, { status: 500, statusText: 'Server Error' });

    expect(component.errors['toni_cans']).toBe('Kaputt');
    expect(component.errors['toni_vomisan']).toBeNull();
  });
});
