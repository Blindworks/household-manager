import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TemperatureService } from './temperature.service';
import { TemperatureSensorSeries } from '../models/temperature.model';

describe('TemperatureService', () => {
  let service: TemperatureService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(TemperatureService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('requests series with range query param', () => {
    const series: TemperatureSensorSeries[] = [];
    service.getSeries('WEEK').subscribe(result => expect(result).toEqual(series));

    const req = httpMock.expectOne(r => r.url === '/api/v1/temperatures');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('range')).toBe('WEEK');
    req.flush(series);
  });

  it('passes the selected range through', () => {
    service.getSeries('DAY').subscribe();

    const req = httpMock.expectOne(r => r.url === '/api/v1/temperatures');
    expect(req.request.params.get('range')).toBe('DAY');
    req.flush([]);
  });
});
