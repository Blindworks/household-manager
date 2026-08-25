import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import { TabletNetworkComponent } from './tablet-network.component';
import { NetworkService } from '../../services/network.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { NetworkHistoryResponse, NetworkStatusResponse, SpeedtestSummary } from '../../models/network.model';
import { insertGaps } from './network-view.util';

describe('TabletNetworkComponent', () => {
  let fixture: ComponentFixture<TabletNetworkComponent>;
  let component: TabletNetworkComponent;
  let networkSpy: jasmine.SpyObj<NetworkService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const status: NetworkStatusResponse = {
    online: true,
    latencyMs: 12,
    gatewayReachable: true,
    lastCheckedAt: '2026-08-25T10:00:00',
    lastSpeedtest: {
      testedAt: '2026-08-25T09:00:00',
      downloadMbps: 120.5,
      uploadMbps: 20.1,
      success: true,
      errorMessage: null
    },
    devices: [
      { id: 1, name: 'Router', host: '192.168.1.1', reachable: true, lastSeenAt: '2026-08-25T10:00:00' },
      { id: 2, name: 'NAS', host: '192.168.1.50', reachable: false, lastSeenAt: null }
    ]
  };

  const history: NetworkHistoryResponse = {
    latency: [
      { time: '2026-08-25T09:00:00', value: 10 },
      { time: '2026-08-25T09:05:00', value: 11 }
    ],
    speedtests: [
      { time: '2026-08-25T09:00:00', downloadMbps: 100, uploadMbps: 18 }
    ]
  };

  beforeEach(async () => {
    networkSpy = jasmine.createSpyObj('NetworkService', ['getStatus', 'getHistory', 'runSpeedtest']);
    networkSpy.getStatus.and.returnValue(of(status));
    networkSpy.getHistory.and.returnValue(of(history));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletNetworkComponent],
      providers: [
        // app-tablet-shell nutzt routerLink fuer die Ansichtsleiste und das
        // Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: NetworkService, useValue: networkSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletNetworkComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('rendert vier Kacheln bei gefuelltem Status/History-Stub', () => {
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-network__card');
    expect(cards.length).toBe(4);
  });

  it('laedt Status und Historie beim Start mit dem Standardzeitraum WEEK', () => {
    expect(networkSpy.getStatus).toHaveBeenCalledTimes(1);
    expect(networkSpy.getHistory).toHaveBeenCalledOnceWith('WEEK');
  });

  it('ueberschreibt bei einem Zeitraumwechsel nicht die neuen Daten mit einer ueberholten Antwort', () => {
    const erster = new Subject<NetworkHistoryResponse>();
    const zweiter = new Subject<NetworkHistoryResponse>();

    networkSpy.getHistory.and.returnValue(erster.asObservable());
    component.setRange('DAY');

    networkSpy.getHistory.and.returnValue(zweiter.asObservable());
    component.setRange('MONTH');

    const neuereHistorie: NetworkHistoryResponse = {
      latency: [{ time: '2026-08-25T09:00:00', value: 99 }],
      speedtests: []
    };
    zweiter.next(neuereHistorie);
    expect(component.historyData).toEqual(neuereHistorie);

    // Die abgeloeste Antwort trifft verspaetet ein und muss wirkungslos bleiben.
    erster.next(history);
    expect(component.historyData).toEqual(neuereHistorie);
  });

  it('meldet einen Fehler, wenn schon der Erstabruf des Status scheitert', () => {
    networkSpy.getStatus.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    const fresh = TestBed.createComponent(TabletNetworkComponent);
    fresh.detectChanges();

    expect(fresh.componentInstance.statusError).not.toBeNull();
    expect(fresh.componentInstance.statusData).toBeNull();
    fresh.destroy();
  });

  it('behaelt bei einem fehlgeschlagenen Hintergrund-Refresh des Status die bisherigen Werte', () => {
    networkSpy.getStatus.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.reload();

    expect(component.statusData?.online).toBeTrue();
    expect(component.statusError).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf der Historie scheitert', () => {
    networkSpy.getHistory.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    const fresh = TestBed.createComponent(TabletNetworkComponent);
    fresh.detectChanges();

    expect(fresh.componentInstance.historyError).not.toBeNull();
    expect(fresh.componentInstance.historyData).toBeNull();
    fresh.destroy();
  });

  it('behaelt bei einem fehlgeschlagenen Hintergrund-Refresh der Historie die bisherigen Werte', () => {
    networkSpy.getHistory.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.reload();

    expect(component.historyData).toEqual(history);
    expect(component.historyError).toBeNull();
  });

  it('laesst einen Ausfall der Historie die Status-Kachel unberuehrt', () => {
    networkSpy.getHistory.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    const fresh = TestBed.createComponent(TabletNetworkComponent);
    fresh.detectChanges();

    expect(fresh.componentInstance.statusData).not.toBeNull();
    expect(fresh.componentInstance.statusError).toBeNull();
    fresh.destroy();
  });

  it('fuegt bei einer Luecke oberhalb der dreifachen Bucketlaenge einen null-Punkt ein', () => {
    // WEEK-Bucket = 30 min, also bricht die Linie ab > 90 min Abstand.
    const points = [
      { time: '2026-08-25T09:00:00', value: 10 },
      { time: '2026-08-25T11:00:00', value: 12 }
    ];
    const result = insertGaps(points, 30);

    expect(result.length).toBe(3);
    expect(result[1].value).toBeNull();
    expect(result[0].value).toBe(10);
    expect(result[2].value).toBe(12);
  });

  it('fuegt bei einer Luecke unterhalb der Schwelle keinen null-Punkt ein', () => {
    const points = [
      { time: '2026-08-25T09:00:00', value: 10 },
      { time: '2026-08-25T09:20:00', value: 12 }
    ];
    expect(insertGaps(points, 30).length).toBe(2);
  });

  it('startet einen Speedtest und laedt bei Erfolg den Status neu', () => {
    const result: SpeedtestSummary = {
      testedAt: '2026-08-25T11:00:00',
      downloadMbps: 130,
      uploadMbps: 22,
      success: true,
      errorMessage: null
    };
    networkSpy.runSpeedtest.and.returnValue(of(result));
    networkSpy.getStatus.calls.reset();

    component.runSpeedtest();

    expect(networkSpy.runSpeedtest).toHaveBeenCalledTimes(1);
    expect(networkSpy.getStatus).toHaveBeenCalledTimes(1);
    expect(component.speedtestError).toBeNull();
  });

  it('zeigt bei einem fehlgeschlagenen Speedtest die Servermeldung in der Kachel', () => {
    networkSpy.runSpeedtest.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 429, error: { message: 'Zu viele Anfragen' } })));

    component.runSpeedtest();

    expect(component.speedtestError).toBe('Zu viele Anfragen');
  });

  it('zeigt eine generische Meldung, wenn der Server keine Nachricht liefert', () => {
    networkSpy.runSpeedtest.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.runSpeedtest();

    expect(component.speedtestError).not.toBeNull();
  });

  it('zeigt "Noch keine Messung" statt eines Farbstatus, wenn nie gemessen wurde', () => {
    networkSpy.getStatus.and.returnValue(of({ ...status, lastCheckedAt: null }));

    const fresh = TestBed.createComponent(TabletNetworkComponent);
    fresh.detectChanges();

    const card = (fresh.nativeElement as HTMLElement).querySelector('.tablet-network__card--status');
    expect(card?.textContent).toContain('Noch keine Messung');
    fresh.destroy();
  });

  it('listet die Geraete mit Erreichbarkeit', () => {
    const card = (fixture.nativeElement as HTMLElement).querySelector('.tablet-network__card--devices');
    expect(card?.textContent).toContain('Router');
    expect(card?.textContent).toContain('NAS');
  });

  it('gibt den Graphen die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Bewusst ein EIGENER Container statt des Elternknotens - siehe Kommentar in
    // tablet-air-quality.component.spec.ts: das Fixture haengt direkt im <body>
    // zusammen mit Karmas eigenen Elementen und den Wurzelknoten schon gelaufener
    // Suiten, die sich sonst dieselbe Hoehe teilen wuerden.
    const host = fixture.nativeElement as HTMLElement;
    const frame = document.createElement('div');
    frame.style.display = 'flex';
    frame.style.flexDirection = 'column';
    document.body.appendChild(frame);
    frame.appendChild(host);

    const chartHeights = (): number[] =>
      Array.from(host.querySelectorAll('.tablet-network__chart'))
        .map(chart => chart.getBoundingClientRect().height);

    frame.style.height = '900px';
    fixture.detectChanges();
    const small = chartHeights();

    frame.style.height = '1200px';
    fixture.detectChanges();
    const large = chartHeights();

    expect(small.length).toBe(2);
    small.forEach(height => expect(height).toBeGreaterThan(80));
    large.forEach((height, i) => expect(height).toBeGreaterThan(small[i] + 100));

    document.body.appendChild(host);
    frame.remove();
  });
});
