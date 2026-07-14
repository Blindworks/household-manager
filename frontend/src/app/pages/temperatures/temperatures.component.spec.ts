import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { TemperaturesComponent } from './temperatures.component';
import { TemperatureService } from '../../services/temperature.service';
import { TemperatureSensorSeries } from '../../models/temperature.model';

describe('TemperaturesComponent', () => {
  let fixture: ComponentFixture<TemperaturesComponent>;
  let component: TemperaturesComponent;
  let serviceSpy: jasmine.SpyObj<TemperatureService>;

  const withHumidity: TemperatureSensorSeries = {
    sensorId: 'zigbee:1', name: 'Wohnzimmer', source: 'ZIGBEE',
    temperature: [{ time: '2026-07-14T10:00:00', value: 21.5 }],
    humidity: [{ time: '2026-07-14T10:00:00', value: 48 }]
  };
  const withoutHumidity: TemperatureSensorSeries = {
    sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER',
    temperature: [{ time: '2026-07-14T10:00:00', value: 12.3 }],
    humidity: []
  };

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('TemperatureService', ['getSeries']);
    serviceSpy.getSeries.and.returnValue(of([withHumidity, withoutHumidity]));

    await TestBed.configureTestingModule({
      imports: [TemperaturesComponent],
      providers: [{ provide: TemperatureService, useValue: serviceSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(TemperaturesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads series for the default range WEEK on init', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('WEEK');
    expect(component.charts.length).toBe(2);
  });

  it('reloads when a different range is selected', () => {
    component.setRange('DAY');
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('DAY');
    expect(component.activeRange).toBe('DAY');
  });

  it('does not reload when the active range is selected again', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEK');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  it('builds a humidity series only when humidity data exists', () => {
    const withHum = component.chartOptionsFor(withHumidity) as { series: unknown[] };
    const withoutHum = component.chartOptionsFor(withoutHumidity) as { series: unknown[] };
    expect(withHum.series.length).toBe(2);
    expect(withoutHum.series.length).toBe(1);
  });

  it('shows the empty state when no sensors are returned', () => {
    serviceSpy.getSeries.and.returnValue(of([]));
    component.setRange('MONTH');
    expect(component.charts.length).toBe(0);
    expect(component.isEmpty).toBeTrue();
  });
});
