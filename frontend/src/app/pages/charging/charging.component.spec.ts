import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ChargingComponent } from './charging.component';
import { ChargingService } from '../../services/charging.service';
import { ChargingStationsResponse } from '../../models/charging.model';

describe('ChargingComponent', () => {
  let fixture: ComponentFixture<ChargingComponent>;
  let component: ChargingComponent;
  let chargingSpy: jasmine.SpyObj<ChargingService>;

  const response: ChargingStationsResponse = {
    configured: true,
    home: { lat: 50, lon: 8 },
    radiusKm: 10,
    minPowerKw: 50,
    lastPolledAt: '2026-09-19T11:58:00',
    stations: [
      { stationId: 'other', name: 'EnBW Rastplatz', lat: 50.02, lon: 8.0, distanceMeters: 2200,
        maxPowerKw: 300, total: 8, free: 6, favorite: false },
      { stationId: 'fav', name: 'Lidl Hauptstr.', lat: 50.01, lon: 8.0, distanceMeters: 1100,
        maxPowerKw: 150, total: 2, free: 1, favorite: true,
        chargePoints: [
          { chargePointId: 'P1', status: 'OCCUPIED', occupiedSince: '2026-09-19T11:20:00', minimumDuration: false },
          { chargePointId: 'P2', status: 'FREE', minimumDuration: false }
        ] }
    ]
  };

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService',
      ['getStations', 'refresh', 'addFavorite', 'removeFavorite', 'getChargePoints']);
    chargingSpy.getChargePoints.and.returnValue(of([]));
    chargingSpy.getStations.and.returnValue(of(response));

    await TestBed.configureTestingModule({
      imports: [ChargingComponent],
      providers: [provideRouter([]), { provide: ChargingService, useValue: chargingSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(ChargingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('rendert Favoriten zuerst mit Stern-Knopf zum Entfernen', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.charging-page__row');
    expect(rows[0].textContent).toContain('Lidl Hauptstr.');
    const star = rows[0].querySelector('.charging-page__star-btn') as HTMLButtonElement;
    expect(star.getAttribute('aria-pressed')).toBe('true');
  });

  it('favorisieren ruft den Service und uebernimmt die Antwort', () => {
    const updated: ChargingStationsResponse = {
      ...response,
      stations: response.stations.map(s => s.stationId === 'other' ? { ...s, favorite: true } : s)
    };
    chargingSpy.addFavorite.and.returnValue(of(updated));

    component.toggleFavorite(response.stations[0]);

    expect(chargingSpy.addFavorite).toHaveBeenCalledOnceWith('other');
    expect(component.data?.stations.find(s => s.stationId === 'other')?.favorite).toBeTrue();
  });

  it('entfavorisieren ruft removeFavorite', () => {
    chargingSpy.removeFavorite.and.returnValue(of(response));

    component.toggleFavorite(response.stations[1]);

    expect(chargingSpy.removeFavorite).toHaveBeenCalledOnceWith('fav');
  });

  it('zeigt die Server-Meldung, wenn Favorisieren scheitert (z. B. 400)', () => {
    chargingSpy.addFavorite.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { message: 'nicht in der aktuellen Umkreisliste' }
    })));

    component.toggleFavorite(response.stations[0]);

    expect(component.actionError).toContain('nicht in der aktuellen Umkreisliste');
  });

  it('laedt beim Antippen einer Zeile die Ladepunkte nach und zeigt sie', () => {
    chargingSpy.getChargePoints.and.returnValue(of([
      { chargePointId: 'X1', status: 'FREE', maxPowerKw: 300, minimumDuration: false, pricePerKwh: 0.59 }
    ]));

    component.showDetails('other');
    fixture.detectChanges();

    expect(chargingSpy.getChargePoints).toHaveBeenCalledOnceWith('other');
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.charging-page__point');
    expect(rows.length).toBe(3);
  });

  it('laedt die Ladepunkte einer geoeffneten Zeile bei jedem Refresh frisch nach', () => {
    chargingSpy.getChargePoints.and.returnValue(of([
      { chargePointId: 'X1', status: 'OCCUPIED', maxPowerKw: 300, occupiedSince: '2026-09-19T09:00:00',
        minimumDuration: false }
    ]));
    component.showDetails('other');
    chargingSpy.getChargePoints.and.returnValue(of([
      { chargePointId: 'X1', status: 'FREE', maxPowerKw: 300, minimumDuration: false }
    ]));
    chargingSpy.refresh.and.returnValue(of(response));

    component.refreshNow();
    fixture.detectChanges();

    expect(chargingSpy.getChargePoints).toHaveBeenCalledTimes(2);
    const other = component.stations.find(s => s.stationId === 'other')!;
    expect(other.chargePoints?.[0].status).toBe('FREE');
    expect(other.free).toBe(1);
  });

  it('der Stern favorisiert, ohne die Zeile zu oeffnen', () => {
    chargingSpy.addFavorite.and.returnValue(of(response));
    const star = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('.charging-page__row')[1].querySelector('.charging-page__star-btn') as HTMLButtonElement;

    star.click();

    expect(chargingSpy.addFavorite).toHaveBeenCalledOnceWith('other');
    expect(chargingSpy.getChargePoints).not.toHaveBeenCalled();
  });

  describe('Supercharger', () => {
    const withSupercharger: ChargingStationsResponse = {
      ...response,
      stations: [
        ...response.stations,
        { stationId: 'sc-7', name: 'Tesla Supercharger Pfungstadt', operator: 'Tesla',
          address: 'Hahnstr. 1, Pfungstadt', lat: 50.03, lon: 8.0, distanceMeters: 3300, maxPowerKw: 250,
          total: 15, free: 0, favorite: false, supercharger: true, teslaOnly: true }
      ]
    };

    const scRow = (): HTMLElement => Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.charging-page__row'))
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
    });

    it('hat keinen Stern-Knopf', () => {
      expect(scRow().querySelector('.charging-page__star-btn')).toBeNull();
    });

    it('laedt beim Antippen keine Ladepunkte', () => {
      chargingSpy.getChargePoints.calls.reset();

      scRow().click();
      fixture.detectChanges();

      expect(chargingSpy.getChargePoints).not.toHaveBeenCalled();
    });

    it('nennt unter der Liste die Datenquelle, aber nur mit mindestens einem Supercharger', () => {
      const note = () => (fixture.nativeElement as HTMLElement).querySelector('.charging-page__source');
      expect(note()?.textContent).toContain('Supercharger-Daten: supercharge.info');

      component.data = response;
      fixture.detectChanges();
      expect(note()).toBeNull();
    });
  });

  it('haelt den Kartencontainer im DOM und zeichnet Marker', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.leaflet-container')).not.toBeNull();
    expect(host.querySelectorAll('.charging-marker').length).toBe(2);
  });

  it('zeigt bei fehlendem Zuhause den Hinweis mit Link zur Admin-Seite', () => {
    chargingSpy.getStations.and.returnValue(of({ configured: false, lastPolledAt: null, stations: [] }));
    const fresh = TestBed.createComponent(ChargingComponent);
    fresh.detectChanges();

    const hint = (fresh.nativeElement as HTMLElement).querySelector('.charging-page__unconfigured');
    expect(hint?.querySelector('a')?.getAttribute('href')).toContain('/admin/charging');
    fresh.destroy();
  });
});
