import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { TabletTeslaComponent } from './tablet-tesla.component';
import { TeslaService } from '../../services/tesla.service';
import { TeslaStatus } from '../../models/tesla.model';
import { WeatherService } from '../../services/weather.service';
import { AppearanceService } from '../../services/appearance.service';

describe('TabletTeslaComponent', () => {
  let fixture: ComponentFixture<TabletTeslaComponent>;
  let tesla: jasmine.SpyObj<TeslaService>;

  beforeEach(async () => {
    tesla = jasmine.createSpyObj<TeslaService>('TeslaService', ['getStatus']);
    tesla.getStatus.and.returnValue(of({ configured: false } as TeslaStatus));
    await TestBed.configureTestingModule({
      imports: [TabletTeslaComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: TeslaService, useValue: tesla },
        { provide: WeatherService, useValue: jasmine.createSpyObj('WeatherService', { getOverview: of(null) }) },
        { provide: AppearanceService, useValue: { isLight$: of(false) } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TabletTeslaComponent);
    fixture.detectChanges();
  });

  it('shows the setup hint when no Tesla is configured', () => {
    const hint = fixture.nativeElement.querySelector('.tesla-card__hint');
    expect(hint?.textContent).toContain('Kein Tesla eingerichtet');
  });
});
