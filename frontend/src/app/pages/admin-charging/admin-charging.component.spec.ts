import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { AdminChargingComponent } from './admin-charging.component';
import { ChargingService } from '../../services/charging.service';
import { TractiveService } from '../../services/tractive.service';
import { ChargingSettings } from '../../models/charging.model';

describe('AdminChargingComponent', () => {
  let fixture: ComponentFixture<AdminChargingComponent>;
  let component: AdminChargingComponent;
  let chargingSpy: jasmine.SpyObj<ChargingService>;
  let tractiveSpy: jasmine.SpyObj<TractiveService>;

  const settings: ChargingSettings = { homeLatitude: 50, homeLongitude: 8, radiusKm: 10, minPowerKw: 50 };

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService',
      ['getSettings', 'saveSettings', 'getStations', 'removeFavorite']);
    // Frische Kopie je Test: die Komponente bindet das Objekt per ngModel und der Schrankentest
    // veraendert es - ein geteiltes Objekt liesse die Tests je nach Reihenfolge kippen.
    chargingSpy.getSettings.and.returnValue(of({ ...settings }));
    chargingSpy.getStations.and.returnValue(of({ configured: true, lastPolledAt: null, stations: [
      { stationId: 'fav', name: 'Lidl', lat: 50, lon: 8, distanceMeters: 100, total: 2, free: 1, favorite: true }
    ] }));
    tractiveSpy = jasmine.createSpyObj('TractiveService', ['getHomeSettings']);

    await TestBed.configureTestingModule({
      imports: [AdminChargingComponent],
      providers: [provideRouter([]),
        { provide: ChargingService, useValue: chargingSpy },
        { provide: TractiveService, useValue: tractiveSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(AdminChargingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('laedt die Einstellungen und zeigt das Formular', () => {
    expect(component.settings.radiusKm).toBe(10);
    expect((fixture.nativeElement as HTMLElement).querySelector('.admin-charging__fields')).not.toBeNull();
  });

  it('blendet das Formular aus, wenn das Laden scheitert', () => {
    chargingSpy.getSettings.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const fresh = TestBed.createComponent(AdminChargingComponent);
    fresh.detectChanges();

    expect(fresh.componentInstance.loadFailed()).toBeTrue();
    expect((fresh.nativeElement as HTMLElement).querySelector('.admin-charging__fields')).toBeNull();
    fresh.destroy();
  });

  it('uebernimmt das Hundetracker-Zuhause auf Knopfdruck', () => {
    tractiveSpy.getHomeSettings.and.returnValue(of({
      homeLatitude: 51.5, homeLongitude: 9.5, homeRadiusMeters: 100, homeArrivalRadiusMeters: 500,
      poweredOffAfterMinutes: 60, poweredOffMinBatteryPercent: 15, homeZoneName: 'Zuhause'
    }));

    component.adoptTractiveHome();

    expect(component.settings.homeLatitude).toBe(51.5);
    expect(component.settings.homeLongitude).toBe(9.5);
  });

  it('sperrt Speichern ausserhalb der Schranken', () => {
    component.settings.radiusKm = 80;
    expect(component.canSave).toBeFalse();
    component.settings.radiusKm = 20;
    component.settings.minPowerKw = 500;
    expect(component.canSave).toBeFalse();
    component.settings.minPowerKw = 50;
    expect(component.canSave).toBeTrue();
  });

  it('listet Favoriten und entfernt sie ueber den Service', () => {
    chargingSpy.removeFavorite.and.returnValue(of({ configured: true, lastPolledAt: null, stations: [] }));

    component.removeFavorite('fav');

    expect(chargingSpy.removeFavorite).toHaveBeenCalledOnceWith('fav');
    expect(component.favorites.length).toBe(0);
  });
});
