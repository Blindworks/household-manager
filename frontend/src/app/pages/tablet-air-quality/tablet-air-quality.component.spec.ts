import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletAirQualityComponent } from './tablet-air-quality.component';
import { AirQualitySeriesService } from '../../services/air-quality-series.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { AirQualitySensorSeries } from '../../models/air-quality-series.model';

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

  function sensors(count: number): AirQualitySensorSeries[] {
    return Array.from({ length: count }, (_, i) => ({
      ...indoor,
      sensorId: `alexa:${i}`,
      name: `Monitor ${i}`
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

  it('lädt beim Start den Standardzeitraum WEEK und baut eine Kachel je Sensor', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('WEEK');
    expect(component.charts.length).toBe(2);
  });

  it('zeigt ab Werk die Feinstaubgruppe mit genau einer Achse', () => {
    expect(component.activeGroup.key).toBe('dust');

    const options = component.chartOptionsFor(outdoor) as {
      series: { name: string }[];
      yAxis: unknown[];
    };
    expect(options.series.map(serie => serie.name)).toEqual(['PM2.5', 'PM10']);
    expect(options.yAxis.length).toBe(1);
  });

  it('zeichnet in einer Gruppe nur die Linien, die der Sensor liefert', () => {
    // Der Amazon-Monitor kennt kein PM10.
    const options = component.chartOptionsFor(indoor) as { series: { name: string }[] };
    expect(options.series.map(serie => serie.name)).toEqual(['PM2.5']);
  });

  it('baut die Kacheln beim Gruppenwechsel ohne neuen Abruf neu', () => {
    serviceSpy.getSeries.calls.reset();
    const before = component.charts[0].options;

    component.setGroup('iaq');

    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
    expect(component.charts[0].options).not.toBe(before);
  });

  it('meldet eine Kachel als leer, wenn der Sensor zur Gruppe nichts liefert', () => {
    component.setGroup('iaq');

    // Der Airrohr-Sensor misst keinen IAQ - statt eines leeren Diagramms
    // zeigt die Kachel einen Hinweis.
    expect(component.charts[0].empty).toBeTrue();
    expect(component.charts[1].empty).toBeFalse();
  });

  it('stellt den juengsten Wert der Gruppe neben den Kachelnamen', () => {
    expect(component.charts[0].currentLabel).toBe('8 µg/m³');

    component.setGroup('co');
    expect(component.charts[1].currentLabel).toBe('0,4 ppm');
  });

  it('faerbt nur den IAQ-Wert nach seiner Stufe', () => {
    expect(component.charts[1].currentLevel).toBeNull();

    component.setGroup('iaq');
    expect(component.charts[1].currentLevel).toBe('good');
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
    const freshFixture = TestBed.createComponent(TabletAirQualityComponent);
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
      Array.from(host.querySelectorAll('.tablet-air__chart'))
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
});
