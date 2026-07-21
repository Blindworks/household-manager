import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { SwitchService } from '../../services/switch.service';
import { WeatherService } from '../../services/weather.service';
import { EnergyLiveService } from '../../services/energy-live.service';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { TemperatureService } from '../../services/temperature.service';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { ModeService } from '../../services/mode.service';
import { SwitchEntity } from '../../models/switch.model';
import { ModeEntity } from '../../models/mode.model';

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
    confirmRequired: false,
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
        // Das Dashboard zieht fuer die Hub-Meldung den WasteCollectionService, der
        // HttpClient injiziert. Hier gegen das Test-Backend gelegt statt gestubbt:
        // diese Specs pruefen die Schalter, nicht die Muellabfuhr.
        provideHttpClient(),
        provideHttpClientTesting(),
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

    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(4, 'tile');
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
    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(4, 'tile');

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

  it('oeffnet bei Bestaetigungspflicht den Dialog statt zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true }));
    tick();

    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.confirmSwitch?.entityId).toBe('switch.kasa_abc');

    discardPeriodicTasks();
  }));

  it('schaltet nach Bestaetigung im Dialog und schliesst ihn', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const guarded = entity({ confirmRequired: true });
    fixture.componentInstance.toggleSwitch(guarded);

    fixture.componentInstance.confirmToggle(guarded);
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();
    expect(fixture.componentInstance.topSwitches[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('abbrechen schliesst den Bestaetigungsdialog ohne zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true }));

    fixture.componentInstance.closeConfirmDialog();
    tick();

    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

    discardPeriodicTasks();
  }));

  it('schalter ohne bestaetigungspflicht schalten weiterhin direkt', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

    discardPeriodicTasks();
  }));

  it('bestaetigungspflichtiger schalter im schalter-dialog oeffnet den bestaetigungsdialog darueber', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openSwitchDialog();
    tick();
    const guarded = entity({ confirmRequired: true });

    fixture.componentInstance.toggleSwitch(guarded);
    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.switchDialogOpen).toBeTrue();
    expect(fixture.componentInstance.confirmSwitch?.entityId).toBe('switch.kasa_abc');

    fixture.componentInstance.confirmToggle(guarded);
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();
    expect(fixture.componentInstance.switchDialogOpen).toBeTrue();

    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Muellabfuhr-Meldung im Intelligence Hub)', () => {
  let wasteServiceSpy: jasmine.SpyObj<WasteCollectionService>;

  /** Der Hub rendert jede Meldung als .lumina__insight — inklusive der Muellabfuhr. */
  function insightTexts(fixture: ComponentFixture<DashboardComponent>): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.lumina__insight')
    ).map(element => (element.textContent ?? '').replace(/\s+/g, ' ').trim());
  }

  beforeEach(async () => {
    wasteServiceSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    wasteServiceSpy.getUpcoming.and.returnValue(of([
      { date: '2026-07-17', label: 'Hausmüll', daysUntil: 1 }
    ]));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

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
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WasteCollectionService, useValue: wasteServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('zeigt die Termine als Meldung im Hub, noch vor den Platzhaltern', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture)[0]).toContain('Müllabfuhr');
    expect(insightTexts(fixture)[0]).toContain('Morgen: Hausmüll');

    discardPeriodicTasks();
  }));

  it('ruft getUpcoming ohne days auf, damit das konfigurierte Fenster gilt', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(wasteServiceSpy.getUpcoming).toHaveBeenCalledWith();

    discardPeriodicTasks();
  }));

  it('laesst den Hub ohne anstehende Termine bei den Platzhaltern', fakeAsync(() => {
    wasteServiceSpy.getUpcoming.and.returnValue(of([]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture).join(' ')).not.toContain('Müllabfuhr');
    expect(insightTexts(fixture)[0]).toContain('Energie-Optimierung');

    discardPeriodicTasks();
  }));

  it('verschweigt die Meldung bei einem API-Fehler, statt das Dashboard zu stoeren', fakeAsync(() => {
    wasteServiceSpy.getUpcoming.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture).join(' ')).not.toContain('Müllabfuhr');
    expect(insightTexts(fixture).length).toBe(3);

    discardPeriodicTasks();
  }));

  it('aktualisiert die Meldung kurz nach Mitternacht, damit "Morgen" nicht stehen bleibt', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    wasteServiceSpy.getUpcoming.calls.reset();
    wasteServiceSpy.getUpcoming.and.returnValue(of([
      { date: '2026-07-17', label: 'Hausmüll', daysUntil: 0 }
    ]));

    // Bis kurz nach dem naechsten Mitternachtswechsel, aber vor dem stuendlichen Takt.
    const now = new Date();
    const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 5);
    tick(midnight.getTime() - now.getTime());
    fixture.detectChanges();

    expect(wasteServiceSpy.getUpcoming).toHaveBeenCalled();
    expect(insightTexts(fixture)[0]).toContain('Heute: Hausmüll');

    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Modus-Leiste)', () => {
  let modeServiceSpy: jasmine.SpyObj<ModeService>;

  const mode = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off',
    ...overrides
  });

  beforeEach(async () => {
    modeServiceSpy = jasmine.createSpyObj('ModeService', ['getModes', 'toggle']);
    modeServiceSpy.getModes.and.returnValue(of([mode()]));
    modeServiceSpy.toggle.and.returnValue(of(mode({ state: 'on' })));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

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
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ModeService, useValue: modeServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('laedt die Modi und rendert sie als Knoepfe', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(modeServiceSpy.getModes).toHaveBeenCalled();
    const button = (fixture.nativeElement as HTMLElement).querySelector('.lumina__mode');
    expect(button?.textContent).toContain('Nachtmodus');

    discardPeriodicTasks();
  }));

  it('markiert einen aktiven Modus', fakeAsync(() => {
    modeServiceSpy.getModes.and.returnValue(of([mode({ state: 'on' })]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector('.lumina__mode');
    expect(button?.classList).toContain('lumina__mode--active');

    discardPeriodicTasks();
  }));

  it('schaltet optimistisch und uebernimmt den Zustand aus der Antwort', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_nachtmodus');
    expect(fixture.componentInstance.modes[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('setzt den Zustand bei einem Schaltfehler zurueck und meldet ihn', fakeAsync(() => {
    modeServiceSpy.toggle.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modes[0].state).toBe('off');
    expect(fixture.componentInstance.modeError).toContain('Nachtmodus');

    discardPeriodicTasks();
  }));

  it('behaelt beim Ladefehler die zuletzt bekannten Modi', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.modes.length).toBe(1);
    modeServiceSpy.getModes.and.returnValue(throwError(() => new Error('kaputt')));

    tick(30000);

    expect(fixture.componentInstance.modes.length).toBe(1);

    discardPeriodicTasks();
  }));
});
