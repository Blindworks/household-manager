import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { WeatherService } from './weather.service';

describe('WeatherService', () => {
  let service: WeatherService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [WeatherService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(WeatherService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads the overview', () => {
    service.getOverview().subscribe(overview => {
      expect(overview.stationId).toBe('10637');
    });
    const req = httpMock.expectOne('/api/v1/weather/overview');
    expect(req.request.method).toBe('GET');
    req.flush({ stationId: '10637', current: null, hourlyForecast: [], warnings: [], nextRain: null });
  });

  it('converts history readingTime to Date', () => {
    service.getHistory().subscribe(readings => {
      expect(readings[0].readingTime instanceof Date).toBe(true);
    });
    const req = httpMock.expectOne('/api/v1/weather/history');
    req.flush([{ id: 1, readingTime: '2026-06-21T12:00:00', temperature: 20.5,
      precipitation: 0, windSpeed: 12, windDirection: 180, humidity: 60, pressure: 1013.2, icon: 1 }]);
  });
});
