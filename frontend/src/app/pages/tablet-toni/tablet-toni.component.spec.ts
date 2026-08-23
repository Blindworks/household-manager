import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TabletToniComponent } from './tablet-toni.component';
import { PetFoodService } from '../../services/pet-food.service';
import { TractiveService } from '../../services/tractive.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { TABLET_VIEWS } from '../../shared/tablet-views';

describe('TabletToniComponent', () => {
  let fixture: ComponentFixture<TabletToniComponent>;
  let petFoodSpy: jasmine.SpyObj<PetFoodService>;
  let tractiveSpy: jasmine.SpyObj<TractiveService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  beforeEach(async () => {
    petFoodSpy = jasmine.createSpyObj('PetFoodService', ['getStatus']);
    petFoodSpy.getStatus.and.returnValue(
      of({ cansRemaining: 34, targetCans: 48, percent: 71, daysRemaining: 34 }));

    tractiveSpy = jasmine.createSpyObj('TractiveService', ['getPets', 'getWalks']);
    tractiveSpy.getPets.and.returnValue(of([]));
    tractiveSpy.getWalks.and.returnValue(of([]));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletToniComponent],
      providers: [
        // Der Rahmen (app-tablet-shell) nutzt routerLink fuer die Ansichtsleiste
        // und zieht das Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: PetFoodService, useValue: petFoodSpy },
        { provide: TractiveService, useValue: tractiveSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletToniComponent);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('steht in der Ansichtsleiste des Tablets', () => {
    // Ohne diesen Eintrag waere die Seite auf dem Tablet nicht erreichbar - im
    // Tablet-Modus blendet die App den Header samt Navigation komplett aus.
    expect(TABLET_VIEWS.some(view => view.route === '/tablet/toni')).toBeTrue();
  });

  it('rahmt sich in die Tablet-Shell mit der Ueberschrift Toni', () => {
    const heading = (fixture.nativeElement as HTMLElement).querySelector('.lumina__heading');
    expect(heading?.textContent?.trim()).toBe('Toni');
  });

  it('zeigt vier Kacheln', () => {
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-toni__card');
    expect(cards.length).toBe(4);
  });
});
