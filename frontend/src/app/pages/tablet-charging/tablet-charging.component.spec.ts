import { AppearanceService } from '../../services/appearance.service';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import { TabletChargingComponent } from './tablet-charging.component';
import { ChargingService } from '../../services/charging.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { ChargingForecast, ChargingStationsResponse } from '../../models/charging.model';

describe('TabletChargingComponent', () => {
  let fixture: ComponentFixture<TabletChargingComponent>;
  let component: TabletChargingComponent;
  let chargingSpy: jasmine.SpyObj<ChargingService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const response: ChargingStationsResponse = {
    configured: true,
    home: { lat: 50, lon: 8 },
    radiusKm: 10,
    minPowerKw: 50,
    lastPolledAt: '2026-09-19T11:58:00',
    stations: [
      {
        stationId: 'other', name: 'EnBW Rastplatz', lat: 50.02, lon: 8.0, distanceMeters: 2200,
        maxPowerKw: 300, total: 8, free: 6, favorite: false
      },
      {
        stationId: 'fav', name: 'Lidl Hauptstr.', operator: 'Lidl', lat: 50.01, lon: 8.0, distanceMeters: 1100,
        maxPowerKw: 150, total: 2, free: 0, favorite: true,
        chargePoints: [
          { chargePointId: 'P1', status: 'OCCUPIED', maxPowerKw: 150, connector: 'CCS',
            occupiedSince: '2026-09-19T11:20:00', minimumDuration: false },
          { chargePointId: 'P2', status: 'OCCUPIED', maxPowerKw: 150, connector: 'CCS',
            occupiedSince: '2026-09-19T11:50:00', minimumDuration: true }
        ]
      }
    ]
  };

  const forecast: ChargingForecast = {
    stationId: 'fav', status: 'OK', observedDays: 20,
    freeProbabilityPercent: { '15': 10, '30': 40, '60': 80 },
    points: [{ chargePointId: 'P1', occupiedSince: '2026-09-19T11:20:00',
      expectedFreeAt: '2026-09-19T12:30:00', expectedFreeLatest: '2026-09-19T13:10:00' }],
    heatmap: Array.from({ length: 7 }, (_, d) => Array.from({ length: 24 }, (_, h) => (h === 3 ? null : (d + h) / 30)))
  };
  const forecastResponse: ChargingStationsResponse = {
    ...response,
    stations: response.stations.map(s => s.favorite
      ? { ...s, forecast: { status: 'OK' as const, freeProbability30min: 40, expectedFreeAt: '2026-09-19T12:30:00' } }
      : s)
  };

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService',
      ['getStations', 'refresh', 'getChargePoints', 'getForecast', 'addFavorite', 'removeFavorite']);
    chargingSpy.getChargePoints.and.returnValue(of([]));
    chargingSpy.getStations.and.returnValue(of(forecastResponse));
    chargingSpy.getForecast.and.returnValue(of(forecast));
    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletChargingComponent],
      providers: [
        provideRouter([]),
        { provide: ChargingService, useValue: chargingSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: AppearanceService, useValue: { isLight$: of(false) } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletChargingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('rendert Favoriten vor den uebrigen Stationen', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-charging__row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Lidl Hauptstr.');
    expect(rows[0].classList).toContain('tablet-charging__row--favorite');
    expect(rows[1].textContent).toContain('EnBW Rastplatz');
  });

  it('zeigt je Ladepunkt eines Favoriten die Belegungsdauer, "mind." bei unbekanntem Beginn', () => {
    const points = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-charging__point');
    expect(points.length).toBe(2);
    expect(points[0].textContent).toContain('seit');
    expect(points[1].textContent).toContain('seit mind.');
  });

  describe('Supercharger', () => {
    const withSupercharger: ChargingStationsResponse = {
      ...forecastResponse,
      stations: [
        ...forecastResponse.stations,
        { stationId: 'sc-7', name: 'Tesla Supercharger Pfungstadt', operator: 'Tesla',
          address: 'Hahnstr. 1, Pfungstadt', lat: 50.03, lon: 8.0, distanceMeters: 3300, maxPowerKw: 250,
          total: 15, free: 0, favorite: false, supercharger: true, teslaOnly: true }
      ]
    };

    const scRow = (): HTMLElement => Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.tablet-charging__row'))
      .find(r => r.textContent?.includes('Tesla Supercharger Pfungstadt'))!;

    beforeEach(() => {
      component.data = withSupercharger;
      fixture.detectChanges();
    });

    it('zeigt Standortdaten mit "Belegung unbekannt" statt frei/gesamt und kennzeichnet nur Tesla', () => {
      const text = scRow().textContent ?? '';
      expect(text).toContain('15 Plätze · bis 250 kW · Belegung unbekannt');
      expect(text).toContain('nur Tesla');
      expect(text).not.toContain('frei');
      expect(scRow().getAttribute('data-tone')).toBe('unknown');
    });

    it('hat keinen Stern-Knopf', () => {
      expect(scRow().querySelector('.tablet-charging__star-btn')).toBeNull();
    });

    it('laedt beim Antippen weder Ladepunkte noch Vorhersage und oeffnet kein Detail', () => {
      chargingSpy.getChargePoints.calls.reset();
      chargingSpy.getForecast.calls.reset();

      scRow().click();
      fixture.detectChanges();

      expect(chargingSpy.getChargePoints).not.toHaveBeenCalled();
      expect(chargingSpy.getForecast).not.toHaveBeenCalled();
      expect(component.detailStationId).toBeNull();
      expect(component.selectedStationId).toBe('sc-7');
    });

    it('nennt unter der Liste die Datenquelle, aber nur mit mindestens einem Supercharger', () => {
      const note = () => (fixture.nativeElement as HTMLElement).querySelector('.tablet-charging__source');
      expect(note()?.textContent).toContain('Supercharger-Daten: supercharge.info');

      component.data = forecastResponse;
      fixture.detectChanges();
      expect(note()).toBeNull();
    });
  });

  describe('Favoriten-Stern', () => {
    const starOf = (index: number): HTMLButtonElement =>
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.tablet-charging__star-btn')[index];

    it('zeigt je Zeile einen Stern, gefuellt nur beim Favoriten', () => {
      expect(starOf(0).textContent?.trim()).toBe('★');
      expect(starOf(0).getAttribute('aria-pressed')).toBe('true');
      expect(starOf(1).textContent?.trim()).toBe('☆');
      expect(starOf(1).getAttribute('aria-pressed')).toBe('false');
    });

    it('macht eine Station per Klick zum Favoriten, ohne die Zeile auszuwaehlen', () => {
      const updated: ChargingStationsResponse = {
        ...response, stations: response.stations.map(s => ({ ...s, favorite: true }))
      };
      chargingSpy.addFavorite.and.returnValue(of(updated));

      starOf(1).click();
      fixture.detectChanges();

      expect(chargingSpy.addFavorite).toHaveBeenCalledWith('other');
      expect(component.selectedStationId).toBeNull();
      expect(component.detailStationId).toBeNull();
      expect(starOf(1).textContent?.trim()).toBe('★');
    });

    it('entfernt einen Favoriten per Klick und schliesst sein offenes Detail', () => {
      component.openDetail('fav');
      const updated: ChargingStationsResponse = {
        ...response, stations: response.stations.map(s => ({ ...s, favorite: false, chargePoints: undefined }))
      };
      chargingSpy.removeFavorite.and.returnValue(of(updated));

      starOf(0).click();
      fixture.detectChanges();

      expect(chargingSpy.removeFavorite).toHaveBeenCalledWith('fav');
      expect(component.detailStationId).toBeNull();
    });

    it('meldet einen Fehler und laesst den Stern unveraendert', () => {
      chargingSpy.addFavorite.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 403, error: { message: 'Verboten' } })));

      starOf(1).click();
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).querySelector('.tablet-charging__error')?.textContent)
        .toContain('Verboten');
      expect(starOf(1).textContent?.trim()).toBe('☆');
      expect(starOf(1).disabled).toBeFalse();
    });
  });

  it('haelt den Kartencontainer immer im DOM und zeichnet Marker je Station', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.leaflet-container')).not.toBeNull();
    expect(host.querySelectorAll('.charging-marker').length).toBe(2);
    expect(host.querySelectorAll('.charging-marker--favorite').length).toBe(1);
  });

  it('setzt den Kartenausschnitt bei einem Refresh nicht zurueck', () => {
    const map = component.mapForTest()!;
    map.setView([50.5, 8.5], 11);

    component.reload();

    expect(map.getZoom()).toBe(11);
    expect(map.getCenter().lat).toBeCloseTo(50.5, 3);
  });

  it('hebt bei Auswahl in der Liste den Pin hervor und schwenkt die Karte dorthin', () => {
    const host = fixture.nativeElement as HTMLElement;
    const map = component.mapForTest()!;
    map.setView([50.5, 8.5], 12);

    component.select('fav');
    fixture.detectChanges();

    const selected = host.querySelectorAll('.charging-marker--selected');
    expect(selected.length).toBe(1);
    expect(selected[0].classList).toContain('charging-marker--favorite');
    // panTo animiert; der Zielmittelpunkt ist die Station, der Zoom bleibt.
    expect(map.getZoom()).toBe(12);
    const rows = host.querySelectorAll('.tablet-charging__row--selected');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('Lidl Hauptstr.');
  });

  it('zeigt bei Favoriten nur den Direktpreis mit Notiz, nie einen EnBW-Preis, und keinen Stift', () => {
    const priced: ChargingStationsResponse = {
      ...forecastResponse,
      stations: forecastResponse.stations.map(s => s.favorite
        ? { ...s, pricePerKwh: 0.39, priceNote: 'Aldi-App',
            chargePoints: s.chargePoints!.map(p => ({ ...p, pricePerKwh: 0.59 })) }
        : s)
    };
    chargingSpy.getStations.and.returnValue(of(priced));
    component.reload();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.tablet-charging__price')?.textContent).toContain('0,39 €/kWh · Aldi-App');
    expect(host.querySelector('.tablet-charging__point-price')).toBeNull();
    expect(host.textContent).not.toContain('0,59');
    expect(host.querySelector('[class*="price-edit"]')).toBeNull();
  });

  it('laedt beim Antippen einer Nicht-Favoritin die Ladepunkte nach und ergaenzt Popup und Liste', () => {
    const host = fixture.nativeElement as HTMLElement;
    chargingSpy.getChargePoints.and.returnValue(of([
      { chargePointId: 'X1', status: 'OCCUPIED', maxPowerKw: 300, occupiedSince: '2026-09-19T11:00:00',
        minimumDuration: false, pricePerKwh: 0.59 }
    ]));

    component.select('other');
    fixture.detectChanges();

    expect(chargingSpy.getChargePoints).toHaveBeenCalledOnceWith('other');
    const popup = component.mapForTest()!.getPane('popupPane')!;
    expect(popup.textContent).toContain('0,59 €/kWh');
    expect(popup.textContent).toContain('belegt');
    // Kopfzahl und Pin folgen den frischen Ladepunkten (1 Punkt, 0 frei), nicht der Umkreisliste (8 von 6).
    expect(popup.textContent).toContain('0/1');
    const pin = host.querySelector('.charging-marker--selected .charging-marker__count');
    expect(pin?.textContent).toBe('0');
    const rows = host.querySelectorAll('.tablet-charging__point');
    expect(rows.length).toBe(3);
  });

  it('laedt die Ladepunkte einer angetippten Station bei jedem Refresh frisch nach', () => {
    const host = fixture.nativeElement as HTMLElement;
    chargingSpy.getChargePoints.and.returnValue(of([
      { chargePointId: 'X1', status: 'OCCUPIED', maxPowerKw: 300, occupiedSince: '2026-09-19T09:00:00',
        minimumDuration: false }
    ]));
    component.select('other');
    chargingSpy.getChargePoints.and.returnValue(of([
      { chargePointId: 'X1', status: 'FREE', maxPowerKw: 300, minimumDuration: false }
    ]));

    component.reload();
    fixture.detectChanges();

    expect(chargingSpy.getChargePoints).toHaveBeenCalledTimes(2);
    const rows = Array.from(host.querySelectorAll('.tablet-charging__point')).map(r => r.textContent ?? '');
    const pointRow = rows.find(r => r.includes('300 kW'));
    expect(pointRow).toContain('frei');
    expect(pointRow).not.toContain('seit');
  });

  it('verwirft nachgeladene Ladepunkte, wenn das Nachladen beim Refresh scheitert', () => {
    const host = fixture.nativeElement as HTMLElement;
    chargingSpy.getChargePoints.and.returnValue(of([
      { chargePointId: 'X1', status: 'OCCUPIED', maxPowerKw: 300, occupiedSince: '2026-09-19T09:00:00',
        minimumDuration: false }
    ]));
    component.select('other');
    chargingSpy.getChargePoints.and.returnValue(throwError(() => new HttpErrorResponse({ status: 502 })));

    component.reload();
    fixture.detectChanges();

    // Nur noch die beiden Ladepunkte des Favoriten - kein eingefrorener "belegt seit"-Stand.
    expect(host.querySelectorAll('.tablet-charging__point').length).toBe(2);
  });

  it('laedt beim erneuten Antippen frisch nach', () => {
    component.select('other');
    component.select('other');

    expect(chargingSpy.getChargePoints).toHaveBeenCalledTimes(2);
  });

  it('laedt fuer einen Favoriten nichts nach - er bringt seine Ladepunkte mit', () => {
    component.select('fav');

    expect(chargingSpy.getChargePoints).not.toHaveBeenCalled();
  });

  it('behaelt die Hervorhebung des Pins ueber einen Refresh hinweg', () => {
    const host = fixture.nativeElement as HTMLElement;
    component.select('other');

    component.reload();

    const selected = host.querySelectorAll('.charging-marker--selected');
    expect(selected.length).toBe(1);
    expect(selected[0].classList).not.toContain('charging-marker--favorite');
  });

  it('zeigt einen Hinweis statt Karte, wenn kein Zuhause konfiguriert ist', () => {
    chargingSpy.getStations.and.returnValue(of({ configured: false, lastPolledAt: null, stations: [] }));
    const fresh = TestBed.createComponent(TabletChargingComponent);
    fresh.detectChanges();

    expect((fresh.nativeElement as HTMLElement).querySelector('.tablet-charging__unconfigured')).not.toBeNull();
    fresh.destroy();
  });

  it('meldet einen Fehler nur beim Erstabruf, ein Hintergrund-Refresh behaelt die Werte', () => {
    chargingSpy.getStations.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.reload();
    expect(component.data?.stations.length).toBe(2);
    expect(component.error).toBeNull();

    const fresh = TestBed.createComponent(TabletChargingComponent);
    fresh.detectChanges();
    expect(fresh.componentInstance.error).not.toBeNull();
    fresh.destroy();
  });

  it('zeigt die 429-Meldung des Servers beim erzwungenen Abruf inline', () => {
    chargingSpy.refresh.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 429, error: { message: 'Der letzte Abruf war gerade eben.' }
    })));

    component.refreshNow();

    expect(component.refreshError).toBe('Der letzte Abruf war gerade eben.');
  });

  it('bestellt einen laufenden Abruf ab, bevor der naechste startet', () => {
    const erster = new Subject<ChargingStationsResponse>();
    const zweiter = new Subject<ChargingStationsResponse>();
    chargingSpy.getStations.and.returnValue(erster.asObservable());
    component.reload();
    chargingSpy.getStations.and.returnValue(zweiter.asObservable());
    component.reload();

    const neu: ChargingStationsResponse = { ...response, stations: [] };
    zweiter.next(neu);
    erster.next(response);

    expect(component.data?.stations.length).toBe(0);
  });

  it('gibt Karte und Liste die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Eigener Rahmen statt body (Karma-Geschwister teilen sich sonst die Hoehe; siehe tablet-toni).
    const host = fixture.nativeElement as HTMLElement;
    const frame = document.createElement('div');
    frame.style.display = 'flex';
    frame.style.flexDirection = 'column';
    document.body.appendChild(frame);
    frame.appendChild(host);
    const mapHeight = (): number =>
      host.querySelector('.tablet-charging__map')!.getBoundingClientRect().height;

    frame.style.height = '900px';
    fixture.detectChanges();
    const small = mapHeight();
    frame.style.height = '1200px';
    fixture.detectChanges();
    const large = mapHeight();

    expect(small).toBeGreaterThan(300);
    expect(large).toBeGreaterThan(small + 250);

    document.body.appendChild(host);
    frame.remove();
  });

  describe('Vorhersage', () => {
    const host = () => fixture.nativeElement as HTMLElement;
    const rows = () => host().querySelectorAll('.tablet-charging__row');

    it('zeigtPrognosezeileBeiFavoriten', () => {
      const line = rows()[0].querySelector('.tablet-charging__forecast');
      expect(line?.textContent).toContain('40 % in 30 min');
    });

    it('keinePrognosezeileBeiNichtFavoriten', () => {
      expect(rows()[1].querySelector('.tablet-charging__forecast')).toBeNull();
    });

    it('listenTippAufFavoritOeffnetDetail', () => {
      (rows()[0] as HTMLElement).click();
      fixture.detectChanges();
      expect(chargingSpy.getForecast).toHaveBeenCalledWith('fav');
      expect(component.detailStationId).toBe('fav');
      expect(host().querySelector('.tablet-charging__detail')).not.toBeNull();
    });

    it('listenTippAufNichtFavoritOeffnetKeinDetail', () => {
      (rows()[1] as HTMLElement).click();
      fixture.detectChanges();
      expect(component.detailStationId).toBeNull();
      expect(chargingSpy.getForecast).not.toHaveBeenCalled();
    });

    it('listenTippAufNichtFavoritSchliesstOffenesDetail', () => {
      (rows()[0] as HTMLElement).click();
      fixture.detectChanges();
      expect(component.detailStationId).toBe('fav');

      (rows()[1] as HTMLElement).click();
      fixture.detectChanges();

      expect(component.detailStationId).toBeNull();
      expect(component.selectedStationId).toBe('other');
      expect(host().querySelector('.tablet-charging__detail')).toBeNull();
      expect(host().querySelector('.tablet-charging__map--hidden')).toBeNull();
    });

    it('zeigtBelegtenPunktOhneSchaetzungMitBeginn', () => {
      chargingSpy.getForecast.and.returnValue(of({ ...forecast, points: [
        ...forecast.points,
        { chargePointId: 'P2', occupiedSince: '2026-09-19T11:50:00' }
      ] }));
      component.openDetail('fav');
      fixture.detectChanges();
      const items = Array.from(host().querySelectorAll('.tablet-charging__detail-points li'))
        .map(li => li.textContent?.trim());
      expect(items.length).toBe(2);
      expect(items[1]).toContain('belegt seit 11:50');
    });

    it('markerTippOeffnetKeinDetail', () => {
      component.select('fav', { fromMap: true });
      fixture.detectChanges();
      expect(component.detailStationId).toBeNull();
      expect(chargingSpy.getForecast).not.toHaveBeenCalled();
    });

    it('kartencontainerBleibtImDomBeiOffenemDetail', () => {
      component.openDetail('fav');
      fixture.detectChanges();
      const map = host().querySelector('.tablet-charging__map');
      expect(map).not.toBeNull();
      expect(map!.classList).toContain('tablet-charging__map--hidden');
    });

    it('schliessenZeigtKarteUndRuftInvalidateSize', fakeAsync(() => {
      component.openDetail('fav');
      fixture.detectChanges();
      const spy = spyOn(component.mapForTest()!, 'invalidateSize');

      component.closeDetail();
      fixture.detectChanges();
      tick(0);

      expect(component.detailStationId).toBeNull();
      expect(host().querySelector('.tablet-charging__detail')).toBeNull();
      expect(host().querySelector('.tablet-charging__map--hidden')).toBeNull();
      expect(spy).toHaveBeenCalled();
    }));

    it('zuWenigDatenZeigtHinweis', () => {
      chargingSpy.getForecast.and.returnValue(of({ ...forecast, status: 'INSUFFICIENT_DATA', observedDays: 5 }));
      component.openDetail('fav');
      fixture.detectChanges();
      expect(host().querySelector('.tablet-charging__detail')?.textContent)
        .toContain('Noch zu wenig Daten (5 Tage) – die Vorhersage startet nach 14 Tagen');
      expect(host().querySelectorAll('.tablet-charging__heat-cell').length).toBe(168);
    });

    it('faerbtBelegungsanteilNiedrigGruenUndHochRot', () => {
      const heatmap = Array.from({ length: 7 }, () => Array.from({ length: 24 }, () => 0.1 as number | null));
      heatmap[0][1] = 0.9;
      chargingSpy.getForecast.and.returnValue(of({ ...forecast, heatmap }));
      component.openDetail('fav');
      fixture.detectChanges();
      const cells = host().querySelectorAll('.tablet-charging__heat-cell');
      expect(cells[0].getAttribute('data-level')).toBe('low');
      expect(cells[1].getAttribute('data-level')).toBe('full');
    });

    it('heatmapHat168Zellen', () => {
      component.openDetail('fav');
      fixture.detectChanges();
      const cells = host().querySelectorAll('.tablet-charging__heat-cell');
      expect(cells.length).toBe(168);
      expect(cells[3].getAttribute('data-level')).toBe('none');
      expect(host().querySelectorAll('.tablet-charging__heat-cell--now').length).toBe(1);
    });

    it('zeigtFreiZeitUndWahrscheinlichkeiten', () => {
      component.openDetail('fav');
      fixture.detectChanges();
      const text = host().querySelector('.tablet-charging__detail')?.textContent ?? '';
      expect(text).toContain('frei ca. 12:30, meist bis 13:10');
      expect(text).toContain('Frei in 15 / 30 / 60 min: 10 % / 40 % / 80 %');
    });

    it('refreshFehlerBehaeltVorhersage', () => {
      component.openDetail('fav');
      fixture.detectChanges();
      chargingSpy.getForecast.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      component.reload();
      fixture.detectChanges();

      expect(chargingSpy.getForecast).toHaveBeenCalledTimes(2);
      expect(component.forecast).toBe(forecast);
      expect(host().querySelectorAll('.tablet-charging__heat-cell').length).toBe(168);
    });
  });
});
