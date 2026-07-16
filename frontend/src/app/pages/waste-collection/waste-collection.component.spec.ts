import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { WasteCollectionComponent } from './waste-collection.component';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { AlexaService } from '../../services/alexa.service';
import { WasteCollectionSettings } from '../../models/waste-collection.model';

describe('WasteCollectionComponent', () => {
  let fixture: ComponentFixture<WasteCollectionComponent>;
  let component: WasteCollectionComponent;
  let wasteSpy: jasmine.SpyObj<WasteCollectionService>;

  const settings: WasteCollectionSettings = {
    enabled: true,
    icsUrl: 'https://x/cal.ics',
    lookaheadDays: 3,
    reminderEnabled: true,
    reminderTime: '19:00',
    reminderAlexaSerials: ['DSN1']
  };

  async function setup(): Promise<void> {
    wasteSpy = jasmine.createSpyObj('WasteCollectionService',
      ['getUpcoming', 'getSettings', 'updateSettings', 'getPollingStatus', 'triggerPoll']);
    wasteSpy.getUpcoming.and.returnValue(of([]));
    wasteSpy.getSettings.and.returnValue(of({ ...settings }));
    wasteSpy.updateSettings.and.returnValue(of({ ...settings }));
    wasteSpy.getPollingStatus.and.returnValue(of({
      schedule: 'Taeglich', lastPollTime: null, lastError: null, knownEventCount: 0
    }));
    wasteSpy.triggerPoll.and.returnValue(of(undefined));

    const alexaSpy = jasmine.createSpyObj('AlexaService', ['getDevices']);
    alexaSpy.getDevices.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [WasteCollectionComponent],
      providers: [
        { provide: WasteCollectionService, useValue: wasteSpy },
        { provide: AlexaService, useValue: alexaSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(WasteCollectionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('laedt Settings, Status und Termine beim Start', async () => {
    await setup();
    expect(wasteSpy.getSettings).toHaveBeenCalled();
    expect(wasteSpy.getPollingStatus).toHaveBeenCalled();
    expect(wasteSpy.getUpcoming).toHaveBeenCalledWith(60);
    expect(component.settings?.icsUrl).toBe('https://x/cal.ics');
  });

  it('sendet die Settings beim Speichern', async () => {
    await setup();
    component.settings!.lookaheadDays = 5;

    component.saveSettings();

    expect(wasteSpy.updateSettings).toHaveBeenCalledWith(
      jasmine.objectContaining({ lookaheadDays: 5 }));
    expect(component.saveSuccess).toBeTrue();
    expect(component.saveError).toBe('');
  });

  it('zeigt die Fehlermeldung des Backends beim Speichern', async () => {
    await setup();
    wasteSpy.updateSettings.and.returnValue(
      throwError(() => new Error('Die Kalender-URL muss mit http:// oder https:// beginnen.')));

    component.saveSettings();

    expect(component.saveError).toBe('Die Kalender-URL muss mit http:// oder https:// beginnen.');
    expect(component.saveSuccess).toBeFalse();
  });

  it('laedt nach dem manuellen Abruf Status und Termine neu', async () => {
    await setup();
    wasteSpy.getPollingStatus.calls.reset();
    wasteSpy.getUpcoming.calls.reset();

    component.triggerPoll();

    expect(wasteSpy.triggerPoll).toHaveBeenCalled();
    expect(wasteSpy.getPollingStatus).toHaveBeenCalled();
    expect(wasteSpy.getUpcoming).toHaveBeenCalledWith(60);
  });

  it('uebernimmt die Geraeteauswahl aus dem Picker', async () => {
    await setup();

    component.onSerialsChange(['DSN1', 'DSN2']);

    expect(component.settings!.reminderAlexaSerials).toEqual(['DSN1', 'DSN2']);
  });
});
