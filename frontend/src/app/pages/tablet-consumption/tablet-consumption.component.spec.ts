import { AppearanceService } from '../../services/appearance.service';
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
    currency: 'EUR',
    points: [
      { periodStart: '2026-08-14', label: 'KW 33', consumption: 34, estimated: false, cost: 10.2 },
      { periodStart: '2026-08-21', label: 'KW 34', consumption: 38.08, estimated: true, cost: 11.43 }
    ]
  };
  const wasser: MeterConsumptionSeries = {
    meterType: MeterType.WATER,
    unit: 'm³',
    currency: 'EUR',
    points: [
      { periodStart: '2026-08-21', label: 'KW 34', consumption: 2.4, estimated: false, cost: null }
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
        { provide: WeatherService, useValue: weatherSpy },
        { provide: AppearanceService, useValue: { isLight$: of(false) } }
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

  it('startet jede Kachel im Verbrauchsmodus', () => {
    expect(component.tiles.every(t => t.mode === 'consumption')).toBeTrue();
  });

  it('zeigt im Kostenmodus Kopfwert und Vergleich in Euro', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');

    const tile = component.tiles[0];
    expect(tile.mode).toBe('cost');
    expect(tile.currentLabel).toContain('11,43');
    expect(tile.currentLabel).toContain('€');
    expect(tile.comparison).toBe('+12 % ggü. Vorwoche');
  });

  it('beschriftet die Y-Achse im Kostenmodus in Euro', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');
    const options = component.tiles[0].options as { yAxis: { axisLabel: { formatter: string } } };
    expect(options.yAxis.axisLabel.formatter).toBe('{value} €');
  });

  it('laesst im Kostenmodus Balken ohne Preis weg und nennt ihre Zahl', () => {
    component.setMode(MeterType.WATER, 'cost');
    const tile = component.tiles[1];
    const options = tile.options as { series: { data: unknown[] }[] };
    expect(options.series[0].data.length).toBe(0);
    expect(tile.missingPriceCount).toBe(1);
    expect(tile.hasNoCost).toBeTrue();
  });

  it('laesst im Kostenmodus nur bepreiste Balken bei gemischten Preisen uebrig', () => {
    const gemischt: MeterConsumptionSeries = {
      meterType: MeterType.GAS,
      unit: 'm³',
      currency: 'EUR',
      points: [
        { periodStart: '2026-08-07', label: 'KW 32', consumption: 5, estimated: false, cost: 3.5 },
        { periodStart: '2026-08-14', label: 'KW 33', consumption: 6, estimated: false, cost: null },
        { periodStart: '2026-08-21', label: 'KW 34', consumption: 4, estimated: false, cost: 2.8 }
      ]
    };
    serviceSpy.getSeries.and.returnValue(of([gemischt]));
    component.setRange('WEEKS_8');
    component.setMode(MeterType.GAS, 'cost');

    const tile = component.tiles[0];
    const options = tile.options as { series: { data: unknown[] }[] };
    expect(options.series[0].data.length).toBe(2);
    expect(tile.missingPriceCount).toBe(1);
    expect(tile.hasNoCost).toBeFalse();
  });

  it('zeigt bei fehlenden Preisen "Kein Preis hinterlegt" statt des Diagramms', () => {
    component.setMode(MeterType.WATER, 'cost');
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('.tablet-consumption__card');
    expect(cards[1].textContent).toContain('Kein Preis hinterlegt');
    expect(cards[1].querySelector('.tablet-consumption__chart')).toBeNull();
  });

  it('laesst den Modus einer Kachel den Refresh ueberleben', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');
    component.reload();
    expect(component.tiles[0].mode).toBe('cost');
    expect(component.tiles[1].mode).toBe('consumption');
  });

  it('laesst den Modus einer Kachel den Zeitraumwechsel ueberleben', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');
    component.setRange('WEEKS_8');
    expect(component.tiles[0].mode).toBe('cost');
  });

  it('schaltet ueber die Knoepfe im Kachelkopf um', () => {
    const card = fixture.nativeElement.querySelector('.tablet-consumption__card') as HTMLElement;
    const buttons = card.querySelectorAll('.tablet-consumption__mode-btn');
    expect(buttons.length).toBe(2);
    (buttons[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(component.tiles[0].mode).toBe('cost');
    const refreshedButtons = fixture.nativeElement
      .querySelector('.tablet-consumption__card')
      .querySelectorAll('.tablet-consumption__mode-btn');
    expect(refreshedButtons[1].classList).toContain('tablet-consumption__mode-btn--active');
  });

  it('schaltet ohne Nachladen um', () => {
    serviceSpy.getSeries.calls.reset();
    component.setMode(MeterType.ELECTRICITY, 'cost');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
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

  describe('Jahres-Aufloesung', () => {
    const thisYear = new Date().getFullYear();
    const stromJahre: MeterConsumptionSeries = {
      meterType: MeterType.ELECTRICITY,
      unit: 'kWh',
      currency: 'EUR',
      points: [
        { periodStart: `${thisYear - 1}-01-01`, label: `${thisYear - 1}`, consumption: 3000, estimated: false, cost: 900 },
        { periodStart: `${thisYear}-01-01`, label: `${thisYear}`, consumption: 1200, estimated: false, cost: 360 }
      ]
    };

    beforeEach(() => {
      serviceSpy.getSeries.calls.reset();
      serviceSpy.getSeries.and.returnValue(of([stromJahre]));
      component.setResolution('YEAR');
    });

    it('lädt alle Jahre und bietet nur diesen einen Zeitraum', () => {
      expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('YEARS_ALL');
      expect(component.ranges.map(r => r.label)).toEqual(['Alle Jahre']);
    });

    it('kennzeichnet das laufende Jahr mit "bis heute" und vergleicht nicht', () => {
      expect(component.tiles[0].currentLabel).toBe('1.200,0 kWh');
      expect(component.tiles[0].currentSuffix).toBe('bis heute');
      expect(component.tiles[0].comparison).toBeNull();
    });

    it('zeigt den Zusatz im Kachelkopf', () => {
      fixture.detectChanges();
      const suffix = (fixture.nativeElement as HTMLElement).querySelector('.tablet-consumption__suffix');
      expect(suffix?.textContent?.trim()).toBe('bis heute');
    });
  });

  it('traegt bei Wochen keinen "bis heute"-Zusatz', () => {
    expect(component.tiles[0].currentSuffix).toBeNull();
  });

  describe('Tabellenansicht', () => {
    const thisYear = new Date().getFullYear();
    const thisMonth = String(new Date().getMonth() + 1).padStart(2, '0');
    const jahre: MeterConsumptionSeries[] = [{
      meterType: MeterType.ELECTRICITY,
      unit: 'kWh',
      currency: 'EUR',
      points: [
        { periodStart: `${thisYear - 1}-01-01`, label: '', consumption: 3000, estimated: false, cost: 900 },
        { periodStart: `${thisYear}-01-01`, label: '', consumption: 1200, estimated: false, cost: null }
      ]
    }];
    const monate: MeterConsumptionSeries[] = [{
      meterType: MeterType.ELECTRICITY,
      unit: 'kWh',
      currency: 'EUR',
      points: [
        { periodStart: `${thisYear - 1}-06-01`, label: '', consumption: 250, estimated: false, cost: 75 },
        { periodStart: `${thisYear}-${thisMonth}-01`, label: '', consumption: 100, estimated: true, cost: null }
      ]
    }];

    function stubTable(years = jahre, months = monate): void {
      serviceSpy.getSeries.and.callFake(range =>
        of(range === 'YEARS_ALL' ? years : range === 'MONTHS_ALL' ? months : [strom, wasser]));
    }

    const el = (): HTMLElement => fixture.nativeElement as HTMLElement;

    beforeEach(() => {
      serviceSpy.getSeries.calls.reset();
      stubTable();
      component.setViewMode('table');
      fixture.detectChanges();
    });

    it('lädt Jahres- und Monatsreihe', () => {
      expect(serviceSpy.getSeries).toHaveBeenCalledWith('YEARS_ALL');
      expect(serviceSpy.getSeries).toHaveBeenCalledWith('MONTHS_ALL');
      expect(component.totals?.rows.map(r => r.year)).toEqual([thisYear, thisYear - 1]);
    });

    it('ersetzt Kacheln und Zeitraumknoepfe durch die Tabelle', () => {
      expect(el().querySelector('.tablet-consumption__table')).not.toBeNull();
      expect(el().querySelector('.tablet-consumption__grid')).toBeNull();
      expect(el().querySelector('[aria-label="Zeitraum"]')).toBeNull();
      expect(el().querySelector('[aria-label="Auflösung"]')).toBeNull();
    });

    it('klappt nur das juengste Jahr auf', () => {
      expect(component.isYearExpanded(thisYear)).toBeTrue();
      expect(component.isYearExpanded(thisYear - 1)).toBeFalse();
      expect(el().querySelectorAll('.tablet-consumption__month-row').length).toBe(1);
    });

    it('klappt ein Jahr per Tipp auf und wieder zu', () => {
      const buttons = el().querySelectorAll<HTMLButtonElement>('.tablet-consumption__year-btn');
      buttons[1].click();
      fixture.detectChanges();
      expect(el().querySelectorAll('.tablet-consumption__month-row').length).toBe(2);

      buttons[1].click();
      fixture.detectChanges();
      expect(el().querySelectorAll('.tablet-consumption__month-row').length).toBe(1);
    });

    it('markiert laufende Perioden und Schaetzwerte', () => {
      const text = el().querySelector('.tablet-consumption__table')?.textContent ?? '';
      expect(text).toContain('(laufend)');
      expect(text).toContain('≈');
    });

    it('behält den Aufklappzustand ueber den Refresh', () => {
      component.toggleYear(thisYear);
      component.toggleYear(thisYear - 1);
      component.reload();
      expect(component.isYearExpanded(thisYear)).toBeFalse();
      expect(component.isYearExpanded(thisYear - 1)).toBeTrue();
    });

    it('lädt beim Refresh die Tabellenreihen, nicht den Diagrammzeitraum', () => {
      serviceSpy.getSeries.calls.reset();
      component.reload();
      expect(serviceSpy.getSeries.calls.allArgs().map(a => a[0]).sort()).toEqual(['MONTHS_ALL', 'YEARS_ALL']);
    });

    it('behält bei einem fehlgeschlagenen Refresh die Tabelle', () => {
      serviceSpy.getSeries.and.returnValue(throwError(() => new Error('weg')));
      component.reload();
      expect(component.totals?.rows.length).toBe(2);
      expect(component.errorMessage).toBeNull();
    });

    it('lädt beim Zurueckschalten wieder das Diagramm', () => {
      serviceSpy.getSeries.calls.reset();
      component.setViewMode('chart');
      fixture.detectChanges();
      expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('WEEKS_26');
      expect(el().querySelector('.tablet-consumption__grid')).not.toBeNull();
    });

    it('verwirft eine spaete Tabellenantwort nach dem Zurueckschalten', () => {
      const years$ = new Subject<MeterConsumptionSeries[]>();
      serviceSpy.getSeries.and.callFake(range =>
        range === 'YEARS_ALL' ? years$ : of(range === 'MONTHS_ALL' ? monate : [strom, wasser]));
      component.setViewMode('chart');
      component.setViewMode('table');
      component.setViewMode('chart');
      years$.next([]);
      years$.complete();
      expect(component.viewMode).toBe('chart');
      expect(component.totals?.rows.length).toBe(2);
    });

    it('scrollt innerhalb ihres Rahmens statt die Seite zu verlaengern', () => {
      const vieleMonate: MeterConsumptionSeries[] = [{
        ...monate[0],
        points: Array.from({ length: 12 }, (_, i) => ({
          periodStart: `${thisYear}-${String(i + 1).padStart(2, '0')}-01`,
          label: '', consumption: 100 + i, estimated: false, cost: 30
        }))
      }];
      stubTable(jahre, vieleMonate);
      component.reload();

      const host = el();
      const frame = document.createElement('div');
      frame.style.display = 'flex';
      frame.style.flexDirection = 'column';
      frame.style.height = '600px';
      document.body.appendChild(frame);
      frame.appendChild(host);
      fixture.detectChanges();

      const wrap = host.querySelector('.tablet-consumption__table-wrap') as HTMLElement;
      expect(host.getBoundingClientRect().height).toBeLessThanOrEqual(600);
      expect(wrap.scrollHeight).toBeGreaterThan(wrap.clientHeight);

      // Host zurueck in den body, damit fixture.destroy() unveraendert laeuft.
      document.body.appendChild(host);
      frame.remove();
    });
  });

  it('meldet einen Fehler, wenn schon der Erstabruf der Tabelle scheitert', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('weg')));
    component.setViewMode('table');
    fixture.detectChanges();
    expect(component.errorMessage).toBe('Verbrauchsdaten konnten nicht geladen werden.');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Verbrauchsdaten konnten nicht geladen werden.');
  });
});
