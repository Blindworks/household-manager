import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletTemperaturesComponent } from './tablet-temperatures.component';
import { TemperatureService } from '../../services/temperature.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { TemperatureSensorSeries } from '../../models/temperature.model';

describe('TabletTemperaturesComponent', () => {
  let fixture: ComponentFixture<TabletTemperaturesComponent>;
  let component: TabletTemperaturesComponent;
  let serviceSpy: jasmine.SpyObj<TemperatureService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const withHumidity: TemperatureSensorSeries = {
    sensorId: 'zigbee:1', name: 'Temperatur Aqara Wohnzimmer', source: 'ZIGBEE',
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

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletTemperaturesComponent],
      providers: [
        // Der Rahmen (app-tablet-shell) nutzt routerLink fuer die Ansichtsleiste
        // und zieht das Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: TemperatureService, useValue: serviceSpy },
        { provide: WeatherService, useValue: weatherSpy }
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

  it('zeigt ab Werk nur die Temperatur', () => {
    expect(component.isMetricActive('temperature')).toBeTrue();
    expect(component.isMetricActive('humidity')).toBeFalse();

    const options = component.chartOptionsFor(withHumidity) as {
      series: { name: string }[];
      yAxis: unknown[];
    };
    expect(options.series.length).toBe(1);
    expect(options.series[0].name).toBe('Temperatur');
    expect(options.yAxis.length).toBe(1);
  });

  it('nimmt die Luftfeuchte auf Wunsch als zweite Achse dazu', () => {
    component.toggleMetric('humidity');

    const options = component.chartOptionsFor(withHumidity) as {
      series: { name: string; yAxisIndex: number }[];
      yAxis: unknown[];
    };
    expect(options.series.map(serie => serie.name)).toEqual(['Temperatur', 'Luftfeuchte']);
    expect(options.series[1].yAxisIndex).toBe(1);
    expect(options.yAxis.length).toBe(2);
  });

  it('zeichnet die Luftfeuchte allein auf der ersten Achse', () => {
    component.toggleMetric('humidity');
    component.toggleMetric('temperature');

    const options = component.chartOptionsFor(withHumidity) as {
      series: { name: string; yAxisIndex: number }[];
      yAxis: unknown[];
    };
    expect(options.series.length).toBe(1);
    expect(options.series[0].name).toBe('Luftfeuchte');
    expect(options.series[0].yAxisIndex).toBe(0);
    expect(options.yAxis.length).toBe(1);
  });

  it('laesst die letzte aktive Messgroesse nicht abwaehlen', () => {
    component.toggleMetric('temperature');

    expect(component.isMetricActive('temperature')).toBeTrue();
  });

  it('baut die Kacheln beim Umschalten ohne neuen Abruf neu', () => {
    serviceSpy.getSeries.calls.reset();
    const before = component.charts[0].options;

    component.toggleMetric('humidity');

    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
    expect(component.charts[0].options).not.toBe(before);
  });

  it('meldet eine Kachel als leer, wenn der Sensor zur Auswahl nichts liefert', () => {
    component.toggleMetric('humidity');
    component.toggleMetric('temperature');

    // Der Aussensensor hat keine Feuchtewerte - statt eines leeren Diagramms
    // zeigt die Kachel einen Hinweis.
    expect(component.charts[0].empty).toBeFalse();
    expect(component.charts[1].empty).toBeTrue();
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

  it('gibt den Graphen die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Der Rahmen spielt die Flex-Spalte der App-Shell nach: nur wenn die Kette vom
    // Host bis zum Chart durchgehend ist, waechst der Graph mit dem Bildschirm -
    // sonst bleibt er auf Inhaltshoehe stehen.
    //
    // Bewusst ein EIGENER Container statt des Elternknotens: das Fixture haengt
    // direkt im <body>, und dort stehen auch Karmas eigene Elemente und die
    // Wurzelknoten schon gelaufener Suiten. Macht man den body zur Flex-Spalte,
    // teilen sich all diese Geschwister die 600 px, und der Graph wird je nach
    // Reihenfolge der Suiten winzig - der Test schlug im Gesamtlauf sporadisch fehl.
    const host = fixture.nativeElement as HTMLElement;
    const frame = document.createElement('div');
    frame.style.display = 'flex';
    frame.style.flexDirection = 'column';
    document.body.appendChild(frame);
    frame.appendChild(host);

    const chartHeights = (): number[] =>
      Array.from(host.querySelectorAll('.tablet-temps__chart'))
        .map(chart => chart.getBoundingClientRect().height);

    frame.style.height = '600px';
    fixture.detectChanges();
    const small = chartHeights();

    frame.style.height = '900px';
    fixture.detectChanges();
    const large = chartHeights();

    expect(small.length).toBe(2);
    small.forEach(height => expect(height).toBeGreaterThan(80));
    // 300 px mehr Bildschirm landen in der einen Rasterzeile.
    large.forEach((height, i) => expect(height).toBeGreaterThan(small[i] + 250));

    // Den Host zurueck in den body haengen, damit fixture.destroy() unveraendert laeuft.
    document.body.appendChild(host);
    frame.remove();
  });

  it('beschriftet die Kacheln mit dem Kurznamen des Sensors', () => {
    // "Temperatur Aqara Wohnzimmer" -> "Aqara Wohnzimmer": das fuehrende Wort
    // traegt auf einer Wand voller Temperaturgraphen keine Information.
    expect(component.charts[0].name).toBe('Aqara Wohnzimmer');
    expect(component.charts[1].name).toBe('Außen');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Aqara Wohnzimmer');
  });

  it('zeichnet die Sensornamen hell genug fuer den schwarzen Grund', () => {
    // Die globale Regel h1..h6 { color: var(--color-dark) } hat den Namen
    // schon einmal unlesbar gemacht - hier festgehalten.
    const title = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-temps__card-title') as HTMLElement;
    const channels = (getComputedStyle(title).color.match(/\d+/g) ?? []).map(Number);

    expect(channels.length).toBeGreaterThanOrEqual(3);
    channels.slice(0, 3).forEach(channel => expect(channel).toBeGreaterThan(200));
  });

  it('zeigt den Leerzustand, wenn kein Sensor geliefert wird', () => {
    serviceSpy.getSeries.and.returnValue(of([]));
    component.setRange('DAY');
    expect(component.isEmpty).toBeTrue();
  });

  it('stoppt den Auto-Refresh beim Verlassen der Seite', () => {
    const timerId = 4242;
    spyOn(window, 'setInterval').and.returnValue(timerId as unknown as ReturnType<typeof setInterval>);
    const clearSpy = spyOn(window, 'clearInterval');

    const shortLived = TestBed.createComponent(TabletTemperaturesComponent);
    shortLived.detectChanges();
    shortLived.destroy();

    expect(clearSpy).toHaveBeenCalledWith(timerId);
  });
});
