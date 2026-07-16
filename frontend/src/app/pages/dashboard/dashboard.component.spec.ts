import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { SwitchService } from '../../services/switch.service';
import { WeatherService } from '../../services/weather.service';
import { EnergyLiveService } from '../../services/energy-live.service';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { TemperatureService } from '../../services/temperature.service';
import { SwitchEntity } from '../../models/switch.model';

describe('DashboardComponent (Schalter)', () => {
  let switchServiceSpy: jasmine.SpyObj<SwitchService>;

  const entity = (overrides: Partial<SwitchEntity> = {}): SwitchEntity => ({
    entityId: 'switch.kasa_abc',
    domain: 'SWITCH',
    source: 'KASA',
    displayName: 'Stehlampe',
    state: 'off',
    available: true,
    icon: 'toggle_on',
    toggleCount: 3,
    lastToggledAt: null,
    ...overrides
  });

  beforeEach(async () => {
    switchServiceSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchServiceSpy.getSwitches.and.returnValue(of([entity()]));
    switchServiceSpy.toggle.and.returnValue(of(entity({ state: 'on' })));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        // Das Dashboard nutzt routerLink (Klima-Kachel) und braucht daher einen Router.
        provideRouter([]),
        { provide: SwitchService, useValue: switchServiceSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('laedt die meistgenutzten Schalter fuer die Kachel', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(4);
    expect(fixture.componentInstance.topSwitches.length).toBe(1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Stehlampe');

    discardPeriodicTasks();
  }));

  it('schaltet optimistisch und uebernimmt den Zustand aus der Antwort', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.topSwitches[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('setzt den Zustand bei einem Schaltfehler zurueck', fakeAsync(() => {
    switchServiceSpy.toggle.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();

    expect(fixture.componentInstance.topSwitches[0].state).toBe('off');
    expect(fixture.componentInstance.switchError).toContain('Stehlampe');

    discardPeriodicTasks();
  }));

  it('oeffnet den Dialog und laedt dafuer alle Schalter', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    switchServiceSpy.getSwitches.calls.reset();

    fixture.componentInstance.openSwitchDialog();
    tick();

    expect(fixture.componentInstance.switchDialogOpen).toBeTrue();
    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith();

    discardPeriodicTasks();
  }));

  it('laedt beim Schliessen des Dialogs die Kachel neu', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openSwitchDialog();
    tick();
    switchServiceSpy.getSwitches.calls.reset();

    fixture.componentInstance.closeSwitchDialog();
    tick();

    expect(fixture.componentInstance.switchDialogOpen).toBeFalse();
    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(4);

    discardPeriodicTasks();
  }));

  it('loescht einen alten Fehler, sobald frische Daten ankommen', fakeAsync(() => {
    switchServiceSpy.toggle.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();
    expect(fixture.componentInstance.switchError).not.toBeNull();

    tick(30000);

    expect(fixture.componentInstance.switchError).toBeNull();

    discardPeriodicTasks();
  }));

  it('traegt einen Fehler aus dem Dialog nicht auf die Kachel', fakeAsync(() => {
    switchServiceSpy.toggle.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openSwitchDialog();
    tick();
    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();
    expect(fixture.componentInstance.switchError).not.toBeNull();

    fixture.componentInstance.closeSwitchDialog();
    tick();

    expect(fixture.componentInstance.switchError).toBeNull();

    discardPeriodicTasks();
  }));
});
