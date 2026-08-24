import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { TabletConsumptionComponent } from './tablet-consumption.component';
import { MeterConsumptionSeriesService } from '../../services/meter-consumption-series.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { MeterConsumptionSeries } from '../../models/meter-consumption-series.model';
import { MeterType } from '../../models/meter-reading.model';

describe('TabletConsumptionComponent', () => {
  let fixture: ComponentFixture<TabletConsumptionComponent>;
  let component: TabletConsumptionComponent;
  let serviceSpy: jasmine.SpyObj<MeterConsumptionSeriesService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const strom: MeterConsumptionSeries = {
    meterType: MeterType.ELECTRICITY,
    unit: 'kWh',
    points: [
      { periodStart: '2026-08-14', label: 'KW 33', consumption: 34, estimated: false },
      { periodStart: '2026-08-21', label: 'KW 34', consumption: 38.08, estimated: true }
    ]
  };
  const wasser: MeterConsumptionSeries = {
    meterType: MeterType.WATER,
    unit: 'm³',
    points: [
      { periodStart: '2026-08-21', label: 'KW 34', consumption: 2.4, estimated: false }
    ]
  };

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('MeterConsumptionSeriesService', ['getSeries']);
    serviceSpy.getSeries.and.returnValue(of([strom, wasser]));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletConsumptionComponent],
      providers: [
        // app-tablet-shell nutzt routerLink fuer die Ansichtsleiste und das Wetter
        // fuer die Kopfzeile.
        provideRouter([]),
        { provide: MeterConsumptionSeriesService, useValue: serviceSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletConsumptionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('lädt beim Start den Standardzeitraum WEEKS_26', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('WEEKS_26');
  });

  it('baut je Zaehlertyp eine Kachel mit deutschem Namen', () => {
    expect(component.tiles.map(t => t.name)).toEqual(['Strom', 'Wasser']);
  });

  it('stellt den letzten Wert mit Einheit in den Kachelkopf', () => {
    expect(component.tiles[0].currentLabel).toBe('38,1 kWh');
    expect(component.tiles[1].currentLabel).toBe('2,4 m³');
  });

  it('nennt die Veraenderung gegenueber der Vorperiode', () => {
    expect(component.tiles[0].comparison).toBe('+12 % ggü. Vorwoche');
  });

  it('laesst den Vergleich bei nur einem Punkt weg', () => {
    expect(component.tiles[1].comparison).toBeNull();
  });

  it('faerbt geschaetzte Balken blasser als echte', () => {
    const options = component.tiles[0].options as {
      series: { data: { value: [string, number]; itemStyle: { opacity: number } }[] }[];
    };
    const [echt, geschaetzt] = options.series[0].data;
    expect(echt.itemStyle.opacity).toBe(1);
    expect(geschaetzt.itemStyle.opacity).toBeLessThan(1);
  });

  it('meldet, ob ueberhaupt ein Schaetzwert im Bild ist', () => {
    expect(component.tiles[0].hasEstimated).toBeTrue();
    expect(component.tiles[1].hasEstimated).toBeFalse();
  });

  it('lädt bei einem Zeitraumwechsel genau einmal nach', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEKS_52');
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('WEEKS_52');
    expect(component.activeRange).toBe('WEEKS_52');
  });

  it('lädt nicht nach, wenn der aktive Zeitraum erneut gewaehlt wird', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEKS_26');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  /**
   * Beim Wechsel der Aufloesung gilt der Default der NEUEN Aufloesung, nicht der
   * gleiche Index - sonst spraenge die Ansicht von "8 Wochen" auf "6 Monate".
   */
  it('setzt beim Aufloesungswechsel den Standardzeitraum der neuen Aufloesung', () => {
    component.setRange('WEEKS_8');
    serviceSpy.getSeries.calls.reset();

    component.setResolution('MONTH');

    expect(component.activeResolution).toBe('MONTH');
    expect(component.activeRange).toBe('MONTHS_12');
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('MONTHS_12');
  });

  it('tauscht beim Aufloesungswechsel die Zeitraumknoepfe aus', () => {
    component.setResolution('MONTH');
    expect(component.ranges.map(r => r.value))
      .toEqual(['MONTHS_6', 'MONTHS_12', 'MONTHS_24']);
  });

  it('lädt nicht nach, wenn die aktive Aufloesung erneut gewaehlt wird', () => {
    serviceSpy.getSeries.calls.reset();
    component.setResolution('WEEK');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  /**
   * Ein synchroner of()-Stub verdeckt, ob die Kacheln schon VOR der Antwort neu
   * gebaut werden - deshalb hier ein Subject, das erst auf Kommando liefert.
   */
  it('zeigt beim Zeitraumwechsel bis zur Antwort weiter die alten Kacheln', () => {
    const pending = new Subject<MeterConsumptionSeries[]>();
    serviceSpy.getSeries.and.returnValue(pending.asObservable());

    component.setRange('WEEKS_52');

    expect(component.tiles.length).toBe(2);
    pending.next([strom]);
    pending.complete();
    expect(component.tiles.length).toBe(1);
  });

  /**
   * Zwei schnell aufeinanderfolgende Umschaltungen: trifft die AELTERE Antwort
   * zuletzt ein, darf sie die schon richtigen Kacheln nicht ueberschreiben. Auf
   * einer Wandanzeige stuenden sonst Zahlen zum falschen Zeitraum, ohne dass es
   * jemandem auffiele.
   */
  it('ignoriert eine ueberholte Antwort eines abgeloesten Abrufs', () => {
    const erster = new Subject<MeterConsumptionSeries[]>();
    const zweiter = new Subject<MeterConsumptionSeries[]>();

    serviceSpy.getSeries.and.returnValue(erster.asObservable());
    component.setRange('WEEKS_52');

    serviceSpy.getSeries.and.returnValue(zweiter.asObservable());
    component.setRange('WEEKS_8');

    zweiter.next([wasser]);
    expect(component.tiles.map(t => t.name)).toEqual(['Wasser']);

    // Die abgeloeste Antwort trifft verspaetet ein und muss wirkungslos bleiben.
    erster.next([strom, wasser]);
    expect(component.tiles.map(t => t.name)).toEqual(['Wasser']);
  });

  it('behält bei einem fehlgeschlagenen Refresh die bisherigen Daten', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    component.reload();
    expect(component.tiles.length).toBe(2);
    expect(component.errorMessage).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf scheitert', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    const freshFixture = TestBed.createComponent(TabletConsumptionComponent);
    freshFixture.detectChanges();
    expect(freshFixture.componentInstance.errorMessage).not.toBeNull();
    freshFixture.destroy();
  });

  it('meldet leer, wenn kein Zaehler Werte liefert', () => {
    serviceSpy.getSeries.and.returnValue(of([]));
    component.setRange('WEEKS_8');
    expect(component.isEmpty).toBeTrue();
  });

  it('gibt den Graphen die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Nur wenn die Flex-Kette vom Host bis zum Chart durchgehend ist, waechst der
    // Graph mit dem Bildschirm - sonst bleibt er auf Inhaltshoehe stehen.
    //
    // Bewusst ein EIGENER Container statt des Elternknotens: das Fixture haengt
    // direkt im <body>, und dort stehen auch Karmas eigene Elemente und die
    // Wurzelknoten schon gelaufener Suiten. Macht man den body zur Flex-Spalte,
    // teilen sich all diese Geschwister die Hoehe, und der Graph wird je nach
    // Reihenfolge der Suiten winzig.
    //
    // Gemessen wird bei 900 und 1200 px, nicht bei 600/900 wie in der Temperatur-
    // und Luftqualitaetsansicht: die Kacheln tragen ueber dem Graphen einen
    // zweizeiligen Kopf und darunter die Schaetzwert-Legende, bei 600 px bliebe
    // dem Graphen strukturell kaum etwas, ohne dass an der Kette etwas kaputt waere.
    const host = fixture.nativeElement as HTMLElement;
    const frame = document.createElement('div');
    frame.style.display = 'flex';
    frame.style.flexDirection = 'column';
    document.body.appendChild(frame);
    frame.appendChild(host);

    const chartHeights = (): number[] =>
      Array.from(host.querySelectorAll('.tablet-consumption__chart'))
        .map(chart => chart.getBoundingClientRect().height);

    frame.style.height = '900px';
    fixture.detectChanges();
    const small = chartHeights();

    frame.style.height = '1200px';
    fixture.detectChanges();
    const large = chartHeights();

    expect(small.length).toBe(2);
    small.forEach(height => expect(height).toBeGreaterThan(200));
    // Die drei Kacheln stehen nebeneinander, also kommt der ganze Zuwachs an.
    large.forEach((height, i) => expect(height).toBeGreaterThan(small[i] + 200));

    // Den Host zurueck in den body haengen, damit fixture.destroy() unveraendert laeuft.
    document.body.appendChild(host);
    frame.remove();
  });
});
