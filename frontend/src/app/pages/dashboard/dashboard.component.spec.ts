import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { DashboardComponent } from './dashboard.component';
import { SwitchService } from '../../services/switch.service';
import { WeatherService } from '../../services/weather.service';
import { EnergyLiveService } from '../../services/energy-live.service';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { TemperatureService } from '../../services/temperature.service';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { ModeService } from '../../services/mode.service';
import { SystemService } from '../../services/system.service';
import { NukiService } from '../../services/nuki.service';
import { SwitchEntity } from '../../models/switch.model';
import { ModeEntity } from '../../models/mode.model';
import { NukiLock } from '../../models/nuki.model';
import { PowerConsumerService } from '../../services/power-consumer.service';
import { PowerConsumer } from '../../models/power-consumer.model';
import { CalendarService } from '../../services/calendar.service';
import { CalendarOccurrence } from '../../models/calendar-event.model';
import { CurrentTemperatureReading } from '../../models/temperature.model';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState } from '../../models/entity-state.model';

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

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
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

    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(8, 'tile');
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
    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(8, 'tile');

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

    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true, state: 'on' }));
    tick();

    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.confirmSwitch?.entityId).toBe('switch.kasa_abc');

    discardPeriodicTasks();
  }));

  it('schaltet nach Bestaetigung im Dialog und schliesst ihn', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const guarded = entity({ confirmRequired: true, state: 'on' });
    // confirmToggle loest den Schalter ueber die entityId in topSwitches neu auf (Schutz
    // gegen einen zwischenzeitlichen Refresh) - die Kachel muss ihn deshalb hier schon
    // mit state 'on' enthalten, genau wie beim echten Klick aus der Liste.
    fixture.componentInstance.topSwitches = [guarded];
    fixture.componentInstance.toggleSwitch(guarded);

    fixture.componentInstance.confirmToggle(guarded);
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();
    // switchServiceSpy.toggle liefert im gesamten describe-Block immer eine feste
    // Antwort mit state: 'on' (siehe beforeEach) - unabhaengig davon, was gesendet
    // wurde. Der optimistische Zwischenzustand 'off' wird dadurch sofort wieder
    // ueberschrieben; der real erwartete Endzustand ist deshalb 'on', nicht 'off'.
    // Zugleich ist genau diese Zusicherung der einzige Nachweis in allen 67 Specs
    // dieser Datei, dass die Service-Antwort ueberhaupt angewandt wird - nicht
    // abschwaechen oder entfernen, ohne diese Abdeckung anderswo zu ersetzen.
    expect(fixture.componentInstance.topSwitches[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('schaltet nicht ein, wenn ein Refresh den Schalter zwischenzeitlich als aus meldet', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const guarded = entity({ confirmRequired: true, state: 'on' });
    fixture.componentInstance.topSwitches = [guarded];
    fixture.componentInstance.toggleSwitch(guarded);

    // Hintergrund-Refresh ERSETZT das Array-Element - hier: schon aus.
    fixture.componentInstance.topSwitches = [{ ...guarded, state: 'off' }];
    fixture.componentInstance.confirmToggle(guarded);
    tick();

    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

    discardPeriodicTasks();
  }));

  it('schaltet trotzdem, wenn der Schalter in beiden Listen nicht mehr auffindbar ist', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const guarded = entity({ confirmRequired: true, state: 'on' });
    fixture.componentInstance.topSwitches = [guarded];
    fixture.componentInstance.toggleSwitch(guarded);

    // Ein fehlgeschlagener 30s-Refresh leert topSwitches (catchError(() => of([])),
    // dashboard.component.ts:1511); allSwitches bleibt hier leer, weil der grosse
    // Dialog nicht offen ist. "Nicht gefunden" darf nicht als "ist aus" gelesen
    // werden - toggleSwitch oeffnet den Dialog nur bei state === 'on'.
    fixture.componentInstance.topSwitches = [];
    fixture.componentInstance.confirmToggle(guarded);
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

    discardPeriodicTasks();
  }));

  it('abbrechen schliesst den Bestaetigungsdialog ohne zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true, state: 'on' }));

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
    const guarded = entity({ confirmRequired: true, state: 'on' });
    // confirmToggle loest ueber die entityId in allSwitches neu auf (der Dialog laedt
    // ueber diese Liste, nicht ueber topSwitches) - siehe Kommentar oben.
    fixture.componentInstance.allSwitches = [guarded];

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

  it('einschalten eines geschuetzten schalters fragt nicht nach', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true, state: 'off' }));
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

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

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
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

  it('zeigt die Termine als Meldung im Hub', fakeAsync(() => {
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

  it('zeigt ohne anstehende Termine die Ruhemeldung', fakeAsync(() => {
    wasteServiceSpy.getUpcoming.and.returnValue(of([]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture).join(' ')).not.toContain('Müllabfuhr');
    expect(insightTexts(fixture)[0]).toContain('Alles ruhig');

    discardPeriodicTasks();
  }));

  it('verschweigt die Meldung bei einem API-Fehler, statt das Dashboard zu stoeren', fakeAsync(() => {
    wasteServiceSpy.getUpcoming.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture).join(' ')).not.toContain('Müllabfuhr');
    expect(insightTexts(fixture)[0]).toContain('Alles ruhig');

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

describe('DashboardComponent (Kalender-Termine im Intelligence Hub)', () => {
  let calendarServiceSpy: jasmine.SpyObj<CalendarService>;
  let wasteServiceSpy: jasmine.SpyObj<WasteCollectionService>;

  const occurrence = (overrides: Partial<CalendarOccurrence> = {}): CalendarOccurrence => ({
    eventId: 1,
    occurrenceDate: '2026-07-26',
    recurrenceDate: null,
    title: 'Zahnarzt',
    notes: null,
    category: { id: 3, key: 'health', name: 'Gesundheit', color: '#e57373', icon: null },
    persons: [],
    allDay: false,
    startTime: '09:00',
    endTime: '09:30',
    endDate: null,
    recurring: false,
    daysUntil: 1,
    ...overrides
  });

  /** Der Hub rendert jede Meldung als .lumina__insight — inklusive Muell und Terminen. */
  function insightTexts(fixture: ComponentFixture<DashboardComponent>): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.lumina__insight')
    ).map(element => (element.textContent ?? '').replace(/\s+/g, ' ').trim());
  }

  beforeEach(async () => {
    calendarServiceSpy = jasmine.createSpyObj('CalendarService', ['getUpcoming']);
    calendarServiceSpy.getUpcoming.and.returnValue(of([]));

    wasteServiceSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    wasteServiceSpy.getUpcoming.and.returnValue(of([]));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: CalendarService, useValue: calendarServiceSpy },
        { provide: WasteCollectionService, useValue: wasteServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('zeigt Kalendertermine als Meldungen im Hub', fakeAsync(() => {
    calendarServiceSpy.getUpcoming.and.returnValue(of([occurrence()]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture)[0]).toContain('Zahnarzt');
    expect(insightTexts(fixture)[0]).toContain('Morgen, 09:00 Uhr');

    discardPeriodicTasks();
  }));

  it('ordnet Muell vor Terminen ein', fakeAsync(() => {
    wasteServiceSpy.getUpcoming.and.returnValue(of([
      { date: '2026-07-26', label: 'Hausmüll', daysUntil: 1 }
    ]));
    calendarServiceSpy.getUpcoming.and.returnValue(of([occurrence()]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const texts = insightTexts(fixture);
    expect(texts[0]).toContain('Müllabfuhr');
    expect(texts[1]).toContain('Zahnarzt');
    expect(texts.length).toBe(2);

    discardPeriodicTasks();
  }));

  it('verschweigt Termine bei einem API-Fehler, statt das Dashboard zu stoeren', fakeAsync(() => {
    calendarServiceSpy.getUpcoming.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture).join(' ')).not.toContain('Zahnarzt');
    expect(insightTexts(fixture)[0]).toContain('Alles ruhig');

    discardPeriodicTasks();
  }));

  it('zeigt ohne anstehende Termine die Ruhemeldung', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture)[0]).toContain('Alles ruhig');
    expect(insightTexts(fixture).length).toBe(1);

    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Tuer-offen-Hinweise im Intelligence Hub)', () => {
  let entityStateServiceSpy: jasmine.SpyObj<EntityStateService>;

  const doorContact = (entityId: string, state: string): EntityState => ({
    entityId,
    domain: 'BINARY_SENSOR',
    source: 'ZIGBEE',
    sourceRef: entityId,
    friendlyName: entityId,
    displayName: entityId,
    state,
    attributes: {},
    lastChanged: '2026-08-14T17:46:32',
    lastUpdated: '2026-08-14T17:46:32'
  });

  function insightTexts(fixture: ComponentFixture<DashboardComponent>): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.lumina__insight')
    ).map(element => (element.textContent ?? '').replace(/\s+/g, ' ').trim());
  }

  beforeEach(async () => {
    entityStateServiceSpy = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityStateServiceSpy.getEntities.and.returnValue(of([]));

    const wasteSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    wasteSpy.getUpcoming.and.returnValue(of([]));

    const calendarSpy = jasmine.createSpyObj('CalendarService', ['getUpcoming']);
    calendarSpy.getUpcoming.and.returnValue(of([]));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EntityStateService, useValue: entityStateServiceSpy },
        { provide: WasteCollectionService, useValue: wasteSpy },
        { provide: CalendarService, useValue: calendarSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('zeigt offene Tueren als erste Meldungen im Hub', fakeAsync(() => {
    entityStateServiceSpy.getEntities.and.returnValue(of([
      doorContact('binary_sensor.zigbee_eingangstuer_contact', 'on'),
      doorContact('binary_sensor.zigbee_terassentuer_contact', 'on')
    ]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(entityStateServiceSpy.getEntities).toHaveBeenCalledWith('BINARY_SENSOR', 'ZIGBEE');
    const texts = insightTexts(fixture);
    expect(texts[0]).toContain('Haustür offen');
    expect(texts[1]).toContain('Terrassentür offen');

    discardPeriodicTasks();
  }));

  it('zeigt bei geschlossenen Tueren keine Tuer-Meldung', fakeAsync(() => {
    entityStateServiceSpy.getEntities.and.returnValue(of([
      doorContact('binary_sensor.zigbee_eingangstuer_contact', 'off'),
      doorContact('binary_sensor.zigbee_terassentuer_contact', 'off')
    ]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture).join(' ')).not.toContain('offen');

    discardPeriodicTasks();
  }));

  it('leert die Tuer-Meldungen bei einem API-Fehler, statt das Dashboard zu stoeren', fakeAsync(() => {
    entityStateServiceSpy.getEntities.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(insightTexts(fixture).join(' ')).not.toContain('Haustür');

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

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
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

describe('DashboardComponent (Reboot-Button)', () => {
  let systemServiceSpy: jasmine.SpyObj<SystemService>;

  beforeEach(async () => {
    systemServiceSpy = jasmine.createSpyObj('SystemService', ['reboot', 'ping']);
    systemServiceSpy.reboot.and.returnValue(of(void 0));
    systemServiceSpy.ping.and.returnValue(of({}));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SystemService, useValue: systemServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('rendert den Reboot-Button am Ende der Modus-Leiste', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector('.lumina__mode--reboot');
    expect(button?.textContent).toContain('Reboot');

    discardPeriodicTasks();
  }));

  it('oeffnet beim Klick nur den Bestaetigungsdialog', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openRebootDialog();

    expect(fixture.componentInstance.rebootConfirm).toBeTrue();
    expect(systemServiceSpy.reboot).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));

  it('loest den Reboot erst nach Bestaetigung aus', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    spyOn(fixture.componentInstance, 'reloadPage');

    fixture.componentInstance.openRebootDialog();
    fixture.componentInstance.confirmReboot();
    tick();

    expect(systemServiceSpy.reboot).toHaveBeenCalled();
    expect(fixture.componentInstance.rebootConfirm).toBeFalse();
    expect(fixture.componentInstance.rebootInProgress).toBeTrue();

    discardPeriodicTasks();
  }));

  it('bricht ohne Service-Aufruf ab', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openRebootDialog();
    fixture.componentInstance.cancelReboot();

    expect(fixture.componentInstance.rebootConfirm).toBeFalse();
    expect(systemServiceSpy.reboot).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));

  it('meldet einen Fehler und beendet den Neustart-Zustand', fakeAsync(() => {
    systemServiceSpy.reboot.and.returnValue(throwError(() => new Error('Reboot ist nicht konfiguriert.')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openRebootDialog();
    fixture.componentInstance.confirmReboot();
    tick();

    expect(fixture.componentInstance.rebootInProgress).toBeFalse();
    expect(fixture.componentInstance.modeError).toContain('Reboot ist nicht konfiguriert.');

    discardPeriodicTasks();
  }));

  it('laedt die Seite neu, sobald das Backend wieder antwortet', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const reloadSpy = spyOn(fixture.componentInstance, 'reloadPage');

    fixture.componentInstance.openRebootDialog();
    fixture.componentInstance.confirmReboot();
    tick();
    expect(reloadSpy).not.toHaveBeenCalled();

    tick(20000);

    expect(systemServiceSpy.ping).toHaveBeenCalled();
    expect(reloadSpy).toHaveBeenCalled();

    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Nuki-Tuerschloss)', () => {
  let nukiServiceSpy: jasmine.SpyObj<NukiService>;

  const lock = (overrides: Partial<NukiLock> = {}): NukiLock => ({
    smartlockId: 1,
    name: 'Haustür',
    state: 'locked',
    doorState: 'off',
    batteryCharge: 90,
    batteryCritical: false,
    ...overrides
  });

  beforeEach(async () => {
    nukiServiceSpy = jasmine.createSpyObj('NukiService', ['getLocks', 'sendAction']);
    nukiServiceSpy.getLocks.and.returnValue(of([lock()]));
    nukiServiceSpy.sendAction.and.returnValue(of(void 0));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NukiService, useValue: nukiServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('LOCK schaltet sofort, ohne den Bestaetigungsdialog zu oeffnen', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const target = lock();

    fixture.componentInstance.onNukiAction(target, 'LOCK');
    tick();

    expect(nukiServiceSpy.sendAction).toHaveBeenCalledWith(1, 'LOCK');
    expect(fixture.componentInstance.nukiConfirm).toBeNull();

    discardPeriodicTasks();
  }));

  it('UNLOCK oeffnet den Bestaetigungsdialog, statt sofort zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const target = lock();

    fixture.componentInstance.onNukiAction(target, 'UNLOCK');

    expect(nukiServiceSpy.sendAction).not.toHaveBeenCalled();
    expect(fixture.componentInstance.nukiConfirm).toEqual({ lock: target, action: 'UNLOCK' });

    discardPeriodicTasks();
  }));

  it('UNLATCH oeffnet den Bestaetigungsdialog, statt sofort zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const target = lock();

    fixture.componentInstance.onNukiAction(target, 'UNLATCH');

    expect(nukiServiceSpy.sendAction).not.toHaveBeenCalled();
    expect(fixture.componentInstance.nukiConfirm).toEqual({ lock: target, action: 'UNLATCH' });

    discardPeriodicTasks();
  }));

  it('confirmNukiAction fuehrt die gemerkte Aktion aus und schliesst den Dialog', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const target = lock();
    fixture.componentInstance.onNukiAction(target, 'UNLOCK');

    fixture.componentInstance.confirmNukiAction();
    tick();

    expect(nukiServiceSpy.sendAction).toHaveBeenCalledWith(1, 'UNLOCK');
    expect(fixture.componentInstance.nukiConfirm).toBeNull();

    discardPeriodicTasks();
  }));

  it('cancelNukiAction schliesst den Dialog, ohne zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const target = lock();
    fixture.componentInstance.onNukiAction(target, 'UNLOCK');

    fixture.componentInstance.cancelNukiAction();

    expect(nukiServiceSpy.sendAction).not.toHaveBeenCalled();
    expect(fixture.componentInstance.nukiConfirm).toBeNull();

    discardPeriodicTasks();
  }));

  it('ignoriert onNukiAction, solange fuer das Schloss schon eine Aktion laeuft', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const target = lock();
    fixture.componentInstance.pendingNukiIds.add(target.smartlockId);

    fixture.componentInstance.onNukiAction(target, 'LOCK');
    tick();

    expect(nukiServiceSpy.sendAction).not.toHaveBeenCalled();
    expect(fixture.componentInstance.nukiConfirm).toBeNull();

    discardPeriodicTasks();
  }));

  it('markiert veraltete Daten bei einem Pollfehler, statt sie stillschweigend stehen zu lassen', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.nukiLocks.length).toBe(1);

    nukiServiceSpy.getLocks.and.returnValue(throwError(() => new Error('kaputt')));
    tick(30000);

    expect(fixture.componentInstance.nukiLocks.length).toBe(1);
    expect(fixture.componentInstance.nukiError).toBe('Verbindung unterbrochen – Anzeige evtl. veraltet.');

    discardPeriodicTasks();
  }));

  it('startet zusammengeschrumpft und faehrt beim Klick auf die Kachel aus', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const card: HTMLElement = fixture.nativeElement.querySelector('.lumina__lock-card');

    expect(fixture.componentInstance.nukiExpanded).toBeFalse();
    expect(card.classList).not.toContain('lumina__secured--expanded');

    card.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.nukiExpanded).toBeTrue();
    expect(card.classList).toContain('lumina__secured--expanded');

    tick(6000);
    discardPeriodicTasks();
  }));

  it('klappt die ausgefahrene Kachel nach der Wartezeit von selbst wieder zu', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleNukiCard();
    tick(5999);
    expect(fixture.componentInstance.nukiExpanded).toBeTrue();

    tick(1);
    expect(fixture.componentInstance.nukiExpanded).toBeFalse();

    discardPeriodicTasks();
  }));

  it('haelt die Kachel nach einer Schaltaktion weiter offen, statt mitten im Ergebnis zuzuklappen', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleNukiCard();
    tick(5000);
    fixture.componentInstance.onNukiAction(lock(), 'LOCK');
    tick(5000);

    expect(fixture.componentInstance.nukiExpanded).toBeTrue();

    tick(1000);
    expect(fixture.componentInstance.nukiExpanded).toBeFalse();

    discardPeriodicTasks();
  }));

  it('klick auf einen Schalt-Knopf klappt die Kachel nicht zu', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleNukiCard();
    fixture.detectChanges();

    const lockButton: HTMLElement = fixture.nativeElement.querySelector('.lumina__lock-btn');
    lockButton.click();
    tick();
    fixture.detectChanges();

    expect(nukiServiceSpy.sendAction).toHaveBeenCalledWith(1, 'LOCK');
    expect(fixture.componentInstance.nukiExpanded).toBeTrue();

    tick(6000);
    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Verbraucher-Kachel)', () => {
  let consumerServiceSpy: jasmine.SpyObj<PowerConsumerService>;

  const consumer = (overrides: Partial<PowerConsumer> = {}): PowerConsumer => ({
    entityId: 'sensor.meross_wm_power',
    displayName: 'Waschmaschine',
    powerWatts: 1250,
    unavailable: false,
    ...overrides
  });

  beforeEach(async () => {
    consumerServiceSpy = jasmine.createSpyObj('PowerConsumerService', ['getConsumers', 'getHistory']);
    consumerServiceSpy.getConsumers.and.returnValue(of([consumer()]));
    consumerServiceSpy.getHistory.and.returnValue(of({
      entityId: 'sensor.meross_wm_power',
      displayName: 'Waschmaschine',
      points: [{ time: '2026-07-23T10:00:00', value: 1200 }]
    }));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PowerConsumerService, useValue: consumerServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('laedt die groessten Verbraucher fuer die Kachel', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith(4);
    expect(fixture.componentInstance.topConsumers.length).toBe(1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Waschmaschine');

    discardPeriodicTasks();
  }));

  it('formatiert die Leistung deutsch und ganzzahlig', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.powerLabel(consumer({ powerWatts: 1250.4 })))
      .toBe('1.250 W');

    discardPeriodicTasks();
  }));

  it('zeigt fuer unavailable-Verbraucher einen Strich', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.powerLabel(
      consumer({ powerWatts: null, unavailable: true }))).toBe('–');

    discardPeriodicTasks();
  }));

  it('oeffnet den Dialog und laedt dafuer alle Verbraucher', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    consumerServiceSpy.getConsumers.calls.reset();

    fixture.componentInstance.openConsumerDialog();
    tick();

    expect(fixture.componentInstance.consumerDialogOpen).toBeTrue();
    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith();

    discardPeriodicTasks();
  }));

  it('aktualisiert die Dialogliste im Poll-Takt mit', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openConsumerDialog();
    tick();
    consumerServiceSpy.getConsumers.calls.reset();

    tick(30000);

    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith(4);
    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith();

    discardPeriodicTasks();
  }));

  it('behaelt beim Ladefehler die zuletzt bekannte Liste', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.topConsumers.length).toBe(1);
    consumerServiceSpy.getConsumers.and.returnValue(throwError(() => new Error('kaputt')));

    tick(30000);

    expect(fixture.componentInstance.topConsumers.length).toBe(1);

    discardPeriodicTasks();
  }));

  it('schliessen leert die Dialogliste', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openConsumerDialog();
    tick();

    fixture.componentInstance.closeConsumerDialog();

    expect(fixture.componentInstance.consumerDialogOpen).toBeFalse();
    expect(fixture.componentInstance.allConsumers.length).toBe(0);

    discardPeriodicTasks();
  }));

  it('oeffnet den Graph-Dialog fuer den geklickten Verbraucher', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    expect(fixture.componentInstance.historyConsumer?.entityId).toBe('sensor.meross_wm_power');
    expect(consumerServiceSpy.getHistory).toHaveBeenCalledWith('sensor.meross_wm_power', 'DAY');

    discardPeriodicTasks();
  }));

  it('laedt beim Zeitraumwechsel neu', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();
    consumerServiceSpy.getHistory.calls.reset();

    fixture.componentInstance.setHistoryRange('WEEK');
    tick();

    expect(consumerServiceSpy.getHistory).toHaveBeenCalledWith('sensor.meross_wm_power', 'WEEK');
    expect(fixture.componentInstance.historyRange).toBe('WEEK');

    discardPeriodicTasks();
  }));

  it('laedt bei erneuter Wahl desselben Zeitraums nicht noch einmal', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();
    consumerServiceSpy.getHistory.calls.reset();

    fixture.componentInstance.setHistoryRange('DAY');
    tick();

    expect(consumerServiceSpy.getHistory).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));

  it('zeigt den Leerzustand, solange nichts aufgezeichnet wurde', fakeAsync(() => {
    consumerServiceSpy.getHistory.and.returnValue(of({
      entityId: 'sensor.meross_wm_power', displayName: 'Waschmaschine', points: []
    }));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    expect(fixture.componentInstance.historyEmpty).toBeTrue();

    discardPeriodicTasks();
  }));

  it('meldet einen Ladefehler im Dialog', fakeAsync(() => {
    consumerServiceSpy.getHistory.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    expect(fixture.componentInstance.historyError).toBe('Verlauf konnte nicht geladen werden.');

    discardPeriodicTasks();
  }));

  it('schliessen setzt den Dialog zurueck', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    fixture.componentInstance.closeHistoryDialog();

    expect(fixture.componentInstance.historyConsumer).toBeNull();
    expect(fixture.componentInstance.historyRange).toBe('DAY');

    discardPeriodicTasks();
  }));

  it('laesst den Verbraucher-Dialog offen, wenn der Graph darueber schliesst', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openConsumerDialog();
    tick();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    fixture.componentInstance.closeHistoryDialog();

    expect(fixture.componentInstance.consumerDialogOpen).toBeTrue();

    discardPeriodicTasks();
  }));

  it('laesst die Linie bei einer Messluecke abreissen', fakeAsync(() => {
    consumerServiceSpy.getHistory.and.returnValue(of({
      entityId: 'sensor.meross_wm_power',
      displayName: 'Waschmaschine',
      points: [
        { time: '2026-07-23T10:00:00', value: 1200 },
        { time: '2026-07-23T10:01:00', value: null },
        { time: '2026-07-23T10:02:00', value: 900 }
      ]
    }));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    const series = (fixture.componentInstance.historyOptions as any)['series'][0];
    expect(series.connectNulls).toBeFalse();
    expect(series.data[1][1]).toBeNull();

    discardPeriodicTasks();
  }));

  it('laesst beim schnellen Zeitraumwechsel den zuletzt gewaehlten Zeitraum gewinnen, auch wenn die aeltere Antwort spaeter eintrifft', fakeAsync(() => {
    consumerServiceSpy.getHistory.and.callFake((entityId: string, range: string) => {
      // DAY (die zuerst angefragte, dann verlassene Range) antwortet bewusst langsamer
      // als WEEK, damit die veraltete Antwort nach der aktuellen eintrifft.
      const delayMs = range === 'DAY' ? 100 : 10;
      return of({
        entityId,
        displayName: 'Waschmaschine',
        points: [{ time: '2026-07-23T10:00:00', value: range === 'DAY' ? 111 : 222 }]
      }).pipe(delay(delayMs));
    });
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    fixture.componentInstance.setHistoryRange('WEEK');
    tick(10);
    tick(100);

    expect(fixture.componentInstance.historyRange).toBe('WEEK');
    const series = (fixture.componentInstance.historyOptions as any)['series'][0];
    expect(series.data[0][1]).toBe(222);

    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Klima-Kachel: Sensor-Detaildialog)', () => {
  let temperatureServiceSpy: jasmine.SpyObj<TemperatureService>;

  const wohnzimmer: CurrentTemperatureReading = {
    sensorId: 'zigbee:1',
    name: 'Wohnzimmer',
    source: 'ZIGBEE',
    temperature: 21.4,
    humidity: 48.2,
    measuredAt: new Date().toISOString()
  };

  beforeEach(async () => {
    temperatureServiceSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureServiceSpy.getCurrent.and.returnValue(of([wohnzimmer]));
    temperatureServiceSpy.getSensorSeries.and.returnValue(of({
      sensorId: 'zigbee:1',
      name: 'Wohnzimmer',
      source: 'ZIGBEE' as const,
      temperature: [],
      humidity: []
    }));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureServiceSpy }
      ]
    }).compileComponents();
  });

  it('oeffnet per Klick auf eine Sensorzeile den Dialog mit Temperatur und Feuchte', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const row: HTMLButtonElement = fixture.nativeElement.querySelector('.lumina__climate-row');
    row.click();
    fixture.detectChanges();

    const detail = fixture.componentInstance.sensorDetail;
    expect(detail?.name).toBe('Wohnzimmer');
    expect(detail?.temperatureLabel).toBe('21,4 °C');
    expect(detail?.humidityLabel).toBe('48 %');

    const dialog: HTMLElement = fixture.nativeElement.querySelector('.lumina__dialog--sensor');
    expect(dialog.textContent).toContain('21,4 °C');
    expect(dialog.textContent).toContain('48 %');

    discardPeriodicTasks();
  }));

  it('schliesst den Dialog wieder', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openSensorDialog('zigbee:1');
    fixture.componentInstance.closeSensorDialog();
    fixture.detectChanges();

    expect(fixture.componentInstance.sensorDetail).toBeNull();
    expect(fixture.nativeElement.querySelector('.lumina__dialog--sensor')).toBeNull();

    discardPeriodicTasks();
  }));

  it('zieht den offenen Dialog auf frische Messwerte nach', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openSensorDialog('zigbee:1');

    temperatureServiceSpy.getCurrent.and.returnValue(of([
      { ...wohnzimmer, temperature: 24.0, humidity: 51.0 }
    ]));
    tick(60000);

    expect(fixture.componentInstance.sensorDetail?.temperatureLabel).toBe('24,0 °C');
    expect(fixture.componentInstance.sensorDetail?.humidityLabel).toBe('51 %');

    discardPeriodicTasks();
  }));

  it('behaelt die zuletzt gezeigten Werte, wenn der Abruf fehlschlaegt', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openSensorDialog('zigbee:1');

    temperatureServiceSpy.getCurrent.and.returnValue(throwError(() => new Error('API down')));
    tick(60000);

    expect(fixture.componentInstance.sensorDetail?.temperatureLabel).toBe('21,4 °C');

    discardPeriodicTasks();
  }));

  it('verwirft eine verspaetete Antwort nach Zeitraumwechsel', fakeAsync(() => {
    const series = (range: string) => ({
      sensorId: 'zigbee:1',
      name: 'Wohnzimmer',
      source: 'ZIGBEE' as const,
      temperature: [{ time: '2026-07-31T10:00:00', value: range === 'DAY' ? 20 : 30 }],
      humidity: []
    });
    temperatureServiceSpy.getSensorSeries.and.callFake((_id: string, range: string) =>
      // Die 24-Stunden-Antwort trifft absichtlich SPAETER ein als die 7-Tage-Antwort.
      range === 'DAY' ? of(series('DAY')).pipe(delay(100)) : of(series('WEEK'))
    );
    temperatureServiceSpy.getCurrent.and.returnValue(of([{
      sensorId: 'zigbee:1',
      name: 'Wohnzimmer',
      source: 'ZIGBEE',
      temperature: 21,
      measuredAt: new Date().toISOString()
    } as CurrentTemperatureReading]));

    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    component.openSensorDialog('zigbee:1');
    component.setSensorHistoryRange('WEEK');
    tick(200);

    expect(component.sensorHistoryRange).toBe('WEEK');
    const seriesOption = (component.sensorHistoryOptions as any).series[0];
    expect(seriesOption.data[0][1]).toBe(30);

    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Kachel-Layout in der Tablet-Ansicht)', () => {
  const wohnzimmer: CurrentTemperatureReading = {
    sensorId: 'zigbee:1',
    name: 'Wohnzimmer',
    source: 'ZIGBEE',
    temperature: 21.4,
    humidity: 48.2,
    measuredAt: new Date().toISOString()
  };

  beforeEach(async () => {
    localStorage.removeItem('household-manager-dashboard-tile');
    localStorage.removeItem('household-manager-view-mode');

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([wohnzimmer]));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  afterAll(() => {
    localStorage.removeItem('household-manager-dashboard-tile');
    localStorage.removeItem('household-manager-view-mode');
  });

  it('zeigt in der Website-Ansicht alle drei Kacheln offen und ohne Aufklapp-Kopf', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;

    expect(component.accordionActive).toBeFalse();
    expect(component.isTileOpen('climate')).toBeTrue();
    expect(component.isTileOpen('switches')).toBeTrue();
    expect(component.isTileOpen('consumers')).toBeTrue();
    expect(fixture.nativeElement.querySelector('.lumina__room-toggle')).toBeNull();
    expect(fixture.nativeElement.querySelector('.lumina__room--collapsed')).toBeNull();

    discardPeriodicTasks();
  }));

  it('zeigt in der Tablet-Ansicht Temperaturen und Schalter dauerhaft offen nebeneinander', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.componentInstance.viewMode.toggle();
    fixture.detectChanges();
    const component = fixture.componentInstance;

    expect(component.accordionActive).toBeTrue();
    expect(component.isTileOpen('climate')).toBeTrue();
    expect(component.isTileOpen('switches')).toBeTrue();
    expect(component.isTileCollapsible('climate')).toBeFalse();
    expect(component.isTileCollapsible('switches')).toBeFalse();

    // Nur die Verbraucher-Kachel hat einen Aufklapp-Kopf ...
    const toggles: HTMLButtonElement[] =
      Array.from(fixture.nativeElement.querySelectorAll('.lumina__room-toggle'));
    expect(toggles.length).toBe(1);
    expect(toggles[0].textContent).toContain('Verbraucher');

    // ... und ist als einzige zugeklappt und ueber die volle Breite gesetzt.
    const collapsed = fixture.nativeElement.querySelectorAll('.lumina__room--collapsed');
    expect(collapsed.length).toBe(1);
    expect(collapsed[0].classList).toContain('lumina__consumer-tile');
    expect(collapsed[0].classList).toContain('lumina__room--collapsible');

    // Die beiden offenen Kacheln tragen ihren Titel wieder im Inhalt.
    const staticTiles = fixture.nativeElement.querySelectorAll('.lumina__room--static');
    expect(staticTiles.length).toBe(2);

    discardPeriodicTasks();
  }));

  it('klappt die Verbraucher-Kachel auf, ohne die beiden anderen zu schliessen', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.componentInstance.viewMode.toggle();
    fixture.detectChanges();
    const component = fixture.componentInstance;

    const toggle: HTMLButtonElement =
      fixture.nativeElement.querySelector('.lumina__room-toggle');
    toggle.click();
    fixture.detectChanges();

    expect(component.isTileOpen('consumers')).toBeTrue();
    expect(component.isTileOpen('climate')).toBeTrue();
    expect(component.isTileOpen('switches')).toBeTrue();
    expect(fixture.nativeElement.querySelector('.lumina__room--collapsed')).toBeNull();

    discardPeriodicTasks();
  }));

  it('stellt die Kacheln beim Zurueckschalten auf die Website-Ansicht wieder alle offen dar', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.componentInstance.viewMode.toggle();
    fixture.detectChanges();
    // Verbraucher aufklappen, danach zurueck in die Website-Ansicht.
    fixture.componentInstance.toggleTile('consumers');
    fixture.detectChanges();

    fixture.componentInstance.viewMode.toggle();
    fixture.detectChanges();

    expect(fixture.componentInstance.isTileOpen('climate')).toBeTrue();
    expect(fixture.componentInstance.isTileOpen('consumers')).toBeTrue();
    expect(fixture.nativeElement.querySelector('.lumina__room--collapsed')).toBeNull();
    expect(fixture.nativeElement.querySelector('.lumina__room--static')).toBeNull();

    discardPeriodicTasks();
  }));

  it('zeigt die Leiste mit den Tablet-Ansichten nur in der Tablet-Ansicht', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.lumina__viewbar')).toBeNull();

    fixture.componentInstance.viewMode.toggle();
    fixture.detectChanges();

    const links: HTMLAnchorElement[] =
      Array.from(fixture.nativeElement.querySelectorAll('.lumina__viewbar-btn'));
    expect(links.length).toBe(fixture.componentInstance.tabletViews.length);
    expect(links[0].getAttribute('href')).toBe('/tablet/temperatures');
    expect(links[0].textContent).toContain('Temperaturen');

    discardPeriodicTasks();
  }));
});

describe('DashboardComponent (Aktivierungs-Checks)', () => {
  let modeServiceSpy: jasmine.SpyObj<ModeService>;
  let entityStateServiceSpy: jasmine.SpyObj<EntityStateService>;
  let powerConsumerServiceSpy: jasmine.SpyObj<PowerConsumerService>;

  const toniAllein = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_toni_allein',
    displayName: 'Toni allein',
    icon: 'pets',
    state: 'off',
    ...overrides
  });

  const nachtmodus = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off',
    ...overrides
  });

  const doorContact = (entityId: string, state: string, displayName: string): EntityState => ({
    entityId,
    domain: 'BINARY_SENSOR',
    source: 'ZIGBEE',
    sourceRef: entityId,
    friendlyName: displayName,
    displayName,
    state,
    attributes: { deviceClass: 'door' },
    lastChanged: '2026-08-20T10:00:00Z',
    lastUpdated: '2026-08-20T10:00:00Z'
  });

  const consumer = (displayName: string, powerWatts: number | null): PowerConsumer => ({
    entityId: `sensor.meross_${displayName.toLowerCase()}_power`,
    displayName,
    powerWatts,
    unavailable: powerWatts == null
  });

  beforeEach(async () => {
    modeServiceSpy = jasmine.createSpyObj('ModeService', ['getModes', 'toggle']);
    modeServiceSpy.getModes.and.returnValue(of([toniAllein(), nachtmodus()]));
    modeServiceSpy.toggle.and.returnValue(of(toniAllein({ state: 'on' })));

    entityStateServiceSpy = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityStateServiceSpy.getEntities.and.returnValue(of([]));

    powerConsumerServiceSpy = jasmine.createSpyObj('PowerConsumerService', ['getConsumers', 'getHistory']);
    powerConsumerServiceSpy.getConsumers.and.returnValue(of([]));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ModeService, useValue: modeServiceSpy },
        { provide: EntityStateService, useValue: entityStateServiceSpy },
        { provide: PowerConsumerService, useValue: powerConsumerServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('oeffnet beim Einschalten eines bewachten Modus den Dialog statt zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckMode?.entityId).toBe('input_boolean.manual_toni_allein');
    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));

  it('schaltet einen bewachten Modus beim Ausschalten direkt, ohne Dialog', fakeAsync(() => {
    modeServiceSpy.getModes.and.returnValue(of([toniAllein({ state: 'on' })]));
    modeServiceSpy.toggle.and.returnValue(of(toniAllein({ state: 'off' })));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckMode).toBeNull();
    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_toni_allein');

    discardPeriodicTasks();
  }));

  it('schaltet einen unbewachten Modus direkt in beide Richtungen', fakeAsync(() => {
    modeServiceSpy.toggle.and.returnValue(of(nachtmodus({ state: 'on' })));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[1]);
    tick();

    expect(fixture.componentInstance.modeCheckMode).toBeNull();
    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_nachtmodus');

    discardPeriodicTasks();
  }));

  it('baut die Checks aus den API-Antworten (offene Tuer + Grossverbraucher)', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    entityStateServiceSpy.getEntities.and.returnValue(of([
      doorContact('binary_sensor.zigbee_kueche_contact', 'on', 'Küche Kontakt')
    ]));
    powerConsumerServiceSpy.getConsumers.and.returnValue(of([consumer('Waschmaschine', 800)]));

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckContacts.status).toBe('warning');
    expect(fixture.componentInstance.modeCheckContacts.lines).toEqual(['Küche Kontakt ist offen.']);
    expect(fixture.componentInstance.modeCheckConsumers.status).toBe('warning');
    expect(fixture.componentInstance.modeCheckConsumers.lines).toEqual(['Waschmaschine: 800 W']);

    discardPeriodicTasks();
  }));

  it('zeigt bei einem fehlgeschlagenen Check-Request eine Warnung, der andere Check bleibt unberuehrt', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    entityStateServiceSpy.getEntities.and.returnValue(throwError(() => new Error('kaputt')));
    powerConsumerServiceSpy.getConsumers.and.returnValue(of([]));

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckContacts.lines).toEqual(['Prüfung fehlgeschlagen.']);
    expect(fixture.componentInstance.modeCheckConsumers.status).toBe('ok');

    discardPeriodicTasks();
  }));

  it('schaltet beim Bestaetigen und schliesst den Dialog', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    fixture.componentInstance.confirmModeActivation();
    tick();

    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_toni_allein');
    expect(fixture.componentInstance.modeCheckMode).toBeNull();
    expect(fixture.componentInstance.modes[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('schaltet beim Bestaetigen NICHT, wenn der Modus inzwischen an ist', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    // Hintergrund-Refresh hat den Modus inzwischen eingeschaltet (z. B. via Telegram)
    fixture.componentInstance.modes[0].state = 'on';
    fixture.componentInstance.confirmModeActivation();
    tick();

    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.modeCheckMode).toBeNull();

    discardPeriodicTasks();
  }));

  it('schaltet beim Abbrechen nicht', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    fixture.componentInstance.closeModeCheckDialog();
    tick();

    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.modeCheckMode).toBeNull();

    discardPeriodicTasks();
  }));

  it('rendert den Dialog mit beiden Checks und Warnzeilen', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    entityStateServiceSpy.getEntities.and.returnValue(of([
      doorContact('binary_sensor.zigbee_kueche_contact', 'on', 'Küche Kontakt')
    ]));
    powerConsumerServiceSpy.getConsumers.and.returnValue(of([consumer('Waschmaschine', 800)]));

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const dialog = host.querySelector('.lumina__dialog--modecheck');
    expect(dialog?.textContent).toContain('Toni allein aktivieren?');
    expect(dialog?.textContent).toContain('Küche Kontakt ist offen.');
    expect(dialog?.textContent).toContain('Waschmaschine: 800 W');
    const sections = host.querySelectorAll('.lumina__check[data-status="warning"]');
    expect(sections.length).toBe(2);

    discardPeriodicTasks();
  }));

  it('aktiviert ueber den Dialog-Button', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement)
      .querySelector('.lumina__confirm-go--mode') as HTMLButtonElement;
    button.click();
    tick();

    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_toni_allein');

    discardPeriodicTasks();
  }));

  it('schliesst den Dialog per Escape, ohne zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    fixture.componentInstance.onEscape();
    tick();

    expect(fixture.componentInstance.modeCheckMode).toBeNull();
    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));
});
