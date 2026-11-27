import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AdminTeslaComponent } from './admin-tesla.component';
import { TeslaService } from '../../services/tesla.service';
import { TeslaSettings } from '../../models/tesla.model';

describe('AdminTeslaComponent', () => {
  let fixture: ComponentFixture<AdminTeslaComponent>;
  let component: AdminTeslaComponent;
  let service: jasmine.SpyObj<TeslaService>;
  const settings: TeslaSettings = {
    tokenConfigured: true, vin: null, pollIntervalMinutes: 5, minPollIntervalMinutes: 1, maxPollIntervalMinutes: 60, imageUrl: null
  };

  beforeEach(() => {
    service = jasmine.createSpyObj<TeslaService>('TeslaService',
      ['getSettings', 'saveSettings', 'listVehicles', 'refresh']);
    service.getSettings.and.returnValue(of(settings));
    TestBed.configureTestingModule({
      imports: [AdminTeslaComponent],
      providers: [{ provide: TeslaService, useValue: service }]
    });
    fixture = TestBed.createComponent(AdminTeslaComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('zeigt, dass ein Token hinterlegt ist', () => {
    expect(fixture.nativeElement.textContent).toContain('Token hinterlegt');
  });

  it('lässt das Token beim Speichern ohne Eingabe unverändert', () => {
    service.saveSettings.and.returnValue(of(settings));
    component.pollIntervalMinutes = 10;
    component.save();
    expect(service.saveSettings).toHaveBeenCalledWith({ apiToken: null, vin: null, pollIntervalMinutes: 10, imageUrl: null });
  });

  it('entfernt das Token mit leerem Text', () => {
    service.saveSettings.and.returnValue(of({ ...settings, tokenConfigured: false }));
    component.removeToken();
    expect(service.saveSettings.calls.mostRecent().args[0].apiToken).toBe('');
  });

  it('lädt Fahrzeuge mit dem eingegebenen Token', () => {
    service.listVehicles.and.returnValue(of([{ vin: '5YJ3E7EB0KF000001', displayName: 'Blitz' }]));
    component.tokenInput = ' neu ';
    component.loadVehicles();
    expect(service.listVehicles).toHaveBeenCalledWith('neu');
    expect(component.vehicles.length).toBe(1);
  });

  it('zeigt den Fehler des Backends', () => {
    service.refresh.and.returnValue(throwError(() => new Error('Tessie lehnt den Token ab')));
    component.testNow();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Tessie lehnt den Token ab');
  });
});
