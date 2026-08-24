import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletAirQualityComponent } from './tablet-air-quality.component';
import { AirQualitySeriesService } from '../../services/air-quality-series.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { AirQualitySensorSeries } from '../../models/air-quality-series.model';
import { AIR_QUALITY_LEVEL_COLORS } from '../../shared/air-quality-thresholds.util';

describe('TabletAirQualityComponent', () => {
  let fixture: ComponentFixture<TabletAirQualityComponent>;
  let component: TabletAirQualityComponent;
  let serviceSpy: jasmine.SpyObj<AirQualitySeriesService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const outdoor: AirQualitySensorSeries = {
    sensorId: 'airrohr:local', name: 'Draußen', source: 'AIRROHR',
    metrics: {
      pm25: [{ time: '2026-08-22T10:00:00', value: 8 }],
      pm10: [{ time: '2026-08-22T10:00:00', value: 12 }]
    }
  };
  const indoor: AirQualitySensorSeries = {
    sensorId: 'alexa:appliance-1', name: 'Wohnzimmer', source: 'ALEXA',
    metrics: {
      pm25: [{ time: '2026-08-22T10:00:00', value: 3 }],
      iaq: [{ time: '2026-08-22T10:00:00', value: 72 }],
      voc: [{ time: '2026-08-22T10:00:00', value: 150 }],
      co: [{ time: '2026-08-22T10:00:00', value: 0.4 }]
    }
  };

  /** Sensoren mit je einer Messgroesse - eine Kachel pro Sensor, zum Zaehlen der Spalten. */
  function singleMetricSensors(count: number): AirQualitySensorSeries[] {
    return Array.from({ length: count }, (_, i) => ({
      sensorId: `alexa:${i}`,
      name: `Monitor ${i}`,
      source: 'ALEXA' as const,
      metrics: { iaq: [{ time: '2026-08-22T10:00:00', value: 72 }] }
    }));
  }

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('AirQualitySeriesService', ['getSeries']);
    serviceSpy.getSeries.and.returnValue(of([outdoor, indoor]));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletAirQualityComponent],
      providers: [
        // Der Rahmen (app-tablet-shell) nutzt routerLink fuer die Ansichtsleiste
        // und zieht das Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: AirQualitySeriesService, useValue: serviceSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletAirQualityComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('lädt beim Start den Standardzeitraum WEEK', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('WEEK');
  });

  it('baut je Sensor und Messgroesse eine eigene Kachel', () => {
    // Draussen: PM2.5 + PM10. Drinnen: IAQ, PM2.5, VOC, CO.
    expect(component.charts.length).toBe(6);
    expect(component.charts.map(chart => `${chart.name}/${chart.metricLabel}`)).toEqual([
      'Draußen/PM2.5',
      'Draußen/PM10',
      'Wohnzimmer/Luftqualität (IAQ)',
      'Wohnzimmer/PM2.5',
      'Wohnzimmer/VOC',
      'Wohnzimmer/CO'
    ]);
  });

  it('gibt jeder Kachel genau eine Linie und eine Achse in ihrer Einheit', () => {
    const dust = component.charts[0];
    const options = component.chartOptionsFor(dust) as {
      series: { name: string; data: unknown[]; lineStyle: Record<string, unknown> }[];
      yAxis: { axisLabel: { formatter: string } }[];
    };

    expect(options.series.length).toBe(1);
    expect(options.series[0].name).toBe('PM2.5');
    expect(options.series[0].data).toEqual([['2026-08-22T10:00:00', 8]]);
    expect(options.yAxis.length).toBe(1);
    expect(options.yAxis[0].axisLabel.formatter).toBe('{value} µg/m³');
    // Die Linie traegt keine eigene Farbe - die kommt aus den Grenzwerten.
    expect(options.series[0].lineStyle['color']).toBeUndefined();
  });

  it('faerbt die Linie ueber die Grenzwerte der Messgroesse', () => {
    const options = component.chartOptionsFor(component.charts[0]) as {
      visualMap: { dimension: number; pieces: { lte?: number; color: string }[] };
    };

    // PM2.5: das unterste Band endet laut EEA bei 10 µg/m³.
    expect(options.visualMap.dimension).toBe(1);
    expect(options.visualMap.pieces[0]).toEqual({
      lte: 10,
      color: AIR_QUALITY_LEVEL_COLORS[0]
    });
  });

  it('laesst die Achsenbeschriftung beim einheitenlosen IAQ ohne Einheit', () => {
    const iaq = component.charts[2];
    const options = component.chartOptionsFor(iaq) as {
      yAxis: { axisLabel: { formatter: string } }[];
    };
    expect(options.yAxis[0].axisLabel.formatter).toBe('{value}');
  });

  it('stellt den juengsten Wert mit Einheit neben den Kachelnamen', () => {
    expect(component.charts[0].currentLabel).toBe('8 µg/m³');
    expect(component.charts[5].currentLabel).toBe('0,4 ppm');
  });

  it('faerbt jeden Jetzt-Wert nach seiner Grenzwertstufe', () => {
    // Draussen PM2.5 = 8 µg/m³ -> unterstes Band; IAQ 72 -> gut; CO 0,4 ppm -> gut.
    expect(component.charts[0].currentColor).toBe(AIR_QUALITY_LEVEL_COLORS[0]);
    expect(component.charts[0].currentLevelLabel).toBe('Gut');
    expect(component.charts[2].currentColor).toBe(AIR_QUALITY_LEVEL_COLORS[0]);
    expect(component.charts[5].currentColor).toBe(AIR_QUALITY_LEVEL_COLORS[0]);
  });

  it('zeigt einen schlechten Wert in der Warnfarbe seiner Stufe', () => {
    serviceSpy.getSeries.and.returnValue(of([{
      ...outdoor,
      metrics: { pm25: [{ time: '2026-08-22T10:00:00', value: 60 }] }
    }]));
    component.setRange('DAY');

    // 60 µg/m³ PM2.5 liegt oberhalb des obersten EEA-Bands.
    expect(component.charts[0].currentColor).toBe(AIR_QUALITY_LEVEL_COLORS[4]);
    expect(component.charts[0].currentLevelLabel).toBe('Sehr schlecht');
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
    expect(component.charts.length).toBe(6);
    expect(component.errorMessage).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf scheitert', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    const freshFixture = TestBed.createComponent(TabletAirQualityComponent);
    freshFixture.detectChanges();
    expect(freshFixture.componentInstance.errorMessage).not.toBeNull();
    freshFixture.destroy();
  });

  it('meldet leer, wenn keine Sensoren Werte liefern', () => {
    serviceSpy.getSeries.and.returnValue(of([]));
    component.setRange('DAY');
    expect(component.isEmpty).toBeTrue();
  });

  it('waehlt die Spaltenzahl nach der Kachelzahl', () => {
    // 6 Kacheln aus dem Standardaufbau (2 + 4).
    expect(component.columns).toBe(3);

    serviceSpy.getSeries.and.returnValue(of(singleMetricSensors(2)));
    component.setRange('DAY');
    expect(component.columns).toBe(2);

    serviceSpy.getSeries.and.returnValue(of(singleMetricSensors(9)));
    component.setRange('MONTH');
    expect(component.columns).toBe(4);
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
      Array.from(host.querySelectorAll('.tablet-air__chart'))
        .map(chart => chart.getBoundingClientRect().height);

    frame.style.height = '600px';
    fixture.detectChanges();
    const small = chartHeights();

    frame.style.height = '900px';
    fixture.detectChanges();
    const large = chartHeights();

    expect(small.length).toBe(6);
    small.forEach(height => expect(height).toBeGreaterThan(80));
    // 300 px mehr Bildschirm verteilen sich auf die zwei Rasterzeilen.
    large.forEach((height, i) => expect(height).toBeGreaterThan(small[i] + 100));

    // Den Host zurueck in den body haengen, damit fixture.destroy() unveraendert laeuft.
    document.body.appendChild(host);
    frame.remove();
  });
});
