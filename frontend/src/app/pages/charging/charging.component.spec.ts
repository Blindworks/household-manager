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
        maxPowerKw: 150, total: 2, free: 1, favorite: true, pricePerKwh: 0.39, priceNote: 'Aldi-App',
        chargePoints: [
          { chargePointId: 'P1', status: 'OCCUPIED', occupiedSince: '2026-09-19T11:20:00', minimumDuration: false,
            pricePerKwh: 0.55 },
          { chargePointId: 'P2', status: 'FREE', minimumDuration: false }
        ] }
    ]
  };

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService',
      ['getStations', 'refresh', 'addFavorite', 'removeFavorite', 'getChargePoints', 'setDirectPrice']);
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

  describe('Direktpreis', () => {
    const host = () => fixture.nativeElement as HTMLElement;
    const favRow = () => host().querySelectorAll('.charging-page__row')[0] as HTMLElement;
    const clickIn = (selector: string) => (favRow().querySelector(selector) as HTMLButtonElement).click();
    const typeIn = (selector: string, value: string) => {
      const input = favRow().querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };

    it('zeigt Direktpreis mit Notiz statt des EnBW-Preises und keinen Ladepunkt-Preis bei Favoriten', () => {
      expect(favRow().querySelector('.charging-page__price')?.textContent).toContain('0,39 €/kWh · Aldi-App');
      expect(favRow().querySelector('.charging-page__point-price')).toBeNull();
      expect(favRow().textContent).not.toContain('0,55');
    });

    it('nicht angetippte Nicht-Favoriten haben keinen Stift', () => {
      const otherRow = host().querySelectorAll('.charging-page__row')[1];
      expect(otherRow.querySelector('.charging-page__price-edit')).toBeNull();
    });

    it('der Stift oeffnet den Editor mit den vorhandenen Werten, ohne die Zeile auszuwaehlen', () => {
      clickIn('.charging-page__price-edit');
      fixture.detectChanges();

      expect((favRow().querySelector('.charging-page__price-input') as HTMLInputElement).value).toBe('0,39');
      expect((favRow().querySelector('.charging-page__note-input') as HTMLInputElement).value).toBe('Aldi-App');
      expect(chargingSpy.getChargePoints).not.toHaveBeenCalled();
    });

    it('Speichern sendet die Zahl auch bei Komma-Eingabe und uebernimmt die Antwort', () => {
      const updated: ChargingStationsResponse = {
        ...response,
        stations: response.stations.map(s => s.stationId === 'fav' ? { ...s, pricePerKwh: 0.41, priceNote: 'EnBW' } : s)
      };
      chargingSpy.setDirectPrice.and.returnValue(of(updated));
      clickIn('.charging-page__price-edit');
      fixture.detectChanges();
      typeIn('.charging-page__price-input', '0,41');
      typeIn('.charging-page__note-input', ' EnBW ');

      clickIn('.charging-page__price-save');
      fixture.detectChanges();

      expect(chargingSpy.setDirectPrice).toHaveBeenCalledOnceWith('fav', 0.41, 'EnBW');
      expect(favRow().querySelector('.charging-page__price-form')).toBeNull();
      expect(favRow().querySelector('.charging-page__price')?.textContent).toContain('0,41 €/kWh · EnBW');
    });

    it('Entfernen sendet null fuer Preis und Notiz', () => {
      chargingSpy.setDirectPrice.and.returnValue(of(response));
      clickIn('.charging-page__price-edit');
      fixture.detectChanges();

      clickIn('.charging-page__price-remove');

      expect(chargingSpy.setDirectPrice).toHaveBeenCalledOnceWith('fav', null, null);
    });

    it('ohne Preis gibt es keinen Entfernen-Knopf und der Stift steht trotzdem da', () => {
      const without: ChargingStationsResponse = {
        ...response,
        stations: response.stations.map(s => s.stationId === 'fav'
          ? { ...s, pricePerKwh: undefined, priceNote: undefined } : s)
      };
      component.data = without;
      fixture.detectChanges();

      expect(favRow().querySelector('.charging-page__price')).toBeNull();
      clickIn('.charging-page__price-edit');
      fixture.detectChanges();
      expect(favRow().querySelector('.charging-page__price-remove')).toBeNull();
    });

    it('ungueltige Eingabe zeigt inline einen Fehler und ruft nichts auf', () => {
      clickIn('.charging-page__price-edit');
      fixture.detectChanges();
      typeIn('.charging-page__price-input', 'abc');

      clickIn('.charging-page__price-save');
      fixture.detectChanges();

      expect(chargingSpy.setDirectPrice).not.toHaveBeenCalled();
      expect(favRow().querySelector('.charging-page__price-error')?.textContent).toContain('Preis');
    });

    it('zeigt die Server-Meldung inline, wenn Speichern scheitert', () => {
      chargingSpy.setDirectPrice.and.returnValue(throwError(() => new HttpErrorResponse({
        status: 400, error: { message: 'Die Notiz darf hoechstens 60 Zeichen lang sein.' }
      })));
      clickIn('.charging-page__price-edit');
      fixture.detectChanges();

      clickIn('.charging-page__price-save');
      fixture.detectChanges();

      expect(favRow().querySelector('.charging-page__price-error')?.textContent).toContain('hoechstens 60');
      expect(favRow().querySelector('.charging-page__price-form')).not.toBeNull();
    });

    it('Abbrechen schliesst den Editor ohne Aufruf', () => {
      clickIn('.charging-page__price-edit');
      fixture.detectChanges();

      clickIn('.charging-page__price-cancel');
      fixture.detectChanges();

      expect(favRow().querySelector('.charging-page__price-form')).toBeNull();
      expect(chargingSpy.setDirectPrice).not.toHaveBeenCalled();
    });
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
