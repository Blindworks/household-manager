import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import { TabletChargingComponent } from './tablet-charging.component';
import { ChargingService } from '../../services/charging.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { ChargingStationsResponse } from '../../models/charging.model';

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

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService', ['getStations', 'refresh', 'getChargePoints']);
    chargingSpy.getChargePoints.and.returnValue(of([]));
    chargingSpy.getStations.and.returnValue(of(response));
    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletChargingComponent],
      providers: [
        provideRouter([]),
        { provide: ChargingService, useValue: chargingSpy },
        { provide: WeatherService, useValue: weatherSpy }
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

  it('laedt beim Antippen einer Nicht-Favoritin die Ladepunkte nach und ergaenzt Popup und Liste', () => {
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
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-charging__point');
    expect(rows.length).toBe(3);
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
});
