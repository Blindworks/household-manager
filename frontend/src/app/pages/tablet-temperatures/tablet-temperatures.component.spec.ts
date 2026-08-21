import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletTemperaturesComponent } from './tablet-temperatures.component';
import { TemperatureService } from '../../services/temperature.service';
import { TemperatureSensorSeries } from '../../models/temperature.model';

describe('TabletTemperaturesComponent', () => {
  let fixture: ComponentFixture<TabletTemperaturesComponent>;
  let component: TabletTemperaturesComponent;
  let serviceSpy: jasmine.SpyObj<TemperatureService>;

  const withHumidity: TemperatureSensorSeries = {
    sensorId: 'zigbee:1', name: 'Wohnzimmer', source: 'ZIGBEE',
    temperature: [{ time: '2026-08-21T10:00:00', value: 21.5 }],
    humidity: [{ time: '2026-08-21T10:00:00', value: 48 }]
  };
  const withoutHumidity: TemperatureSensorSeries = {
    sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER',
    temperature: [{ time: '2026-08-21T10:00:00', value: 12.3 }],
    humidity: []
  };

  function sensors(count: number): TemperatureSensorSeries[] {
    return Array.from({ length: count }, (_, i) => ({
      ...withHumidity,
      sensorId: `zigbee:${i}`,
      name: `Sensor ${i}`
    }));
  }

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('TemperatureService', ['getSeries']);
    serviceSpy.getSeries.and.returnValue(of([withHumidity, withoutHumidity]));

    await TestBed.configureTestingModule({
      imports: [TabletTemperaturesComponent],
      providers: [
        // Die Kopfzeile nutzt routerLink fuer den Zurueck-Knopf.
        provideRouter([]),
        { provide: TemperatureService, useValue: serviceSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletTemperaturesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('lädt beim Start den Standardzeitraum WEEK und baut eine Kachel je Sensor', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('WEEK');
    expect(component.charts.length).toBe(2);
  });

  it('zeichnet Temperatur und Luftfeuchte eines Sensors in einem Chart', () => {
    const options = component.chartOptionsFor(withHumidity) as {
      series: unknown[];
      yAxis: unknown[];
    };
    expect(options.series.length).toBe(2);
    expect(options.yAxis.length).toBe(2);
  });

  it('lässt bei einem Sensor ohne Feuchtewerte die zweite Achse weg', () => {
    const options = component.chartOptionsFor(withoutHumidity) as {
      series: unknown[];
      yAxis: unknown[];
    };
    expect(options.series.length).toBe(1);
    expect(options.yAxis.length).toBe(1);
  });

  it('lädt bei einem Zeitraumwechsel genau einmal nach', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('DAY');
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('DAY');
    expect(component.activeRange).toBe('DAY');
  });

  it('lädt nicht nach, wenn der aktive Zeitraum erneut gewählt wird', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEK');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  it('behält bei einem fehlgeschlagenen Refresh die bisherigen Daten', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    component.reload();
    expect(component.charts.length).toBe(2);
    expect(component.errorMessage).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf scheitert', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    const freshFixture = TestBed.createComponent(TabletTemperaturesComponent);
    freshFixture.detectChanges();
    expect(freshFixture.componentInstance.errorMessage).not.toBeNull();
    freshFixture.destroy();
  });

  it('nutzt zwei Spalten bis fünf Sensoren und drei ab sechs', () => {
    serviceSpy.getSeries.and.returnValue(of(sensors(5)));
    component.setRange('DAY');
    expect(component.columns).toBe(2);

    serviceSpy.getSeries.and.returnValue(of(sensors(6)));
    component.setRange('MONTH');
    expect(component.columns).toBe(3);
  });

  it('zeigt den Leerzustand, wenn kein Sensor geliefert wird', () => {
    serviceSpy.getSeries.and.returnValue(of([]));
    component.setRange('DAY');
    expect(component.isEmpty).toBeTrue();
  });

  it('stoppt den Auto-Refresh beim Verlassen der Seite', () => {
    const clearSpy = spyOn(window, 'clearInterval').and.callThrough();
    fixture.destroy();
    expect(clearSpy).toHaveBeenCalled();
  });
});
