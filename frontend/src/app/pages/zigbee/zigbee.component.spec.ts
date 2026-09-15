import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { signal } from '@angular/core';
import { NEVER, Observable, Subject, of, throwError } from 'rxjs';
import { ZigbeeComponent } from './zigbee.component';
import { ZigbeeService } from '../../services/zigbee.service';
import { ZigbeeLiveService } from '../../services/zigbee-live.service';
import { AuthService } from '../../services/auth.service';
import { ZigbeeBridgeStatus, ZigbeeDevice, ZigbeeDeviceHealthStatus, ZigbeeLiveEvent } from '../../models/zigbee.model';

describe('ZigbeeComponent', () => {
  let serviceSpy: jasmine.SpyObj<ZigbeeService>;
  let liveSpy: jasmine.SpyObj<ZigbeeLiveService>;
  let bridgeInfo$: Subject<ZigbeeBridgeStatus>;

  const device = (name: string, status: ZigbeeDeviceHealthStatus, overrides: Partial<ZigbeeDevice> = {}): ZigbeeDevice => ({
    id: 1, friendlyName: name, ieeeAddress: `0x${name}`, knownToBridge: true, type: 'EndDevice',
    powerSource: 'Battery', battery: true, interviewCompleted: true, supported: true,
    model: 'SNZB-03', vendor: 'SONOFF', description: null, lastBatteryPercent: 80, lastLinkQuality: 100,
    lastSeen: null, entities: [],
    health: { status, basis: 'last-message', lastHeardAt: null, silentSeconds: 60 },
    ...overrides
  });

  const bridge = (overrides: Partial<ZigbeeBridgeStatus> = {}): ZigbeeBridgeStatus => ({
    version: '2.1.3', connected: true, registryLoaded: true, permitJoin: false, permitJoinEnd: null,
    availabilityCheckEnabled: true, ...overrides
  });

  function setup(
    isAdmin: boolean,
    devices: ZigbeeDevice[],
    bridgeStatus: ZigbeeBridgeStatus,
    liveEvents$: Observable<ZigbeeLiveEvent> = NEVER
  ): ComponentFixture<ZigbeeComponent> {
    serviceSpy = jasmine.createSpyObj('ZigbeeService', [
      'getDevices', 'getHealth', 'getMeasurements', 'getBridge', 'getBridgeEvents', 'getFlowReferences',
      'openPermitJoin', 'closePermitJoin', 'renameDevice', 'interviewDevice', 'configureDevice',
      'removeDevice', 'purgeLocalData'
    ]);
    serviceSpy.getDevices.and.returnValue(of(devices));
    serviceSpy.getHealth.and.returnValue(of({
      health: 'OK', healthy: true, lastMessageAt: '', silentMinutes: 0, bridgeState: null, offlineDevices: []
    }));
    serviceSpy.getMeasurements.and.returnValue(of([]));
    serviceSpy.getBridge.and.returnValue(of(bridgeStatus));
    serviceSpy.getBridgeEvents.and.returnValue(of([]));
    serviceSpy.getFlowReferences.and.returnValue(of([]));
    serviceSpy.openPermitJoin.and.returnValue(of(void 0));
    serviceSpy.closePermitJoin.and.returnValue(of(void 0));
    serviceSpy.renameDevice.and.returnValue(of(void 0));
    serviceSpy.interviewDevice.and.returnValue(of(void 0));
    serviceSpy.configureDevice.and.returnValue(of(void 0));
    serviceSpy.removeDevice.and.returnValue(of(void 0));
    serviceSpy.purgeLocalData.and.returnValue(of({ friendlyName: 'x', measurements: 0, entities: 0 }));

    bridgeInfo$ = new Subject<ZigbeeBridgeStatus>();
    liveSpy = jasmine.createSpyObj('ZigbeeLiveService', ['getLiveStream', 'getBridgeEvents', 'getBridgeInfo', 'disconnect']);
    liveSpy.getLiveStream.and.returnValue(liveEvents$);
    liveSpy.getBridgeEvents.and.returnValue(NEVER);
    liveSpy.getBridgeInfo.and.returnValue(bridgeInfo$.asObservable());

    TestBed.configureTestingModule({
      imports: [ZigbeeComponent],
      providers: [
        { provide: ZigbeeService, useValue: serviceSpy },
        { provide: ZigbeeLiveService, useValue: liveSpy },
        { provide: AuthService, useValue: { isAdmin: signal(isAdmin) } }
      ]
    });
    const fixture = TestBed.createComponent(ZigbeeComponent);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('zeigt kranke Geraete vor aktiven', () => {
    const fixture = setup(true, [device('Zeta', 'ACTIVE'), device('Alpha', 'SILENT')], bridge());

    const names = Array.from(fixture.nativeElement.querySelectorAll('.device-card h2'))
      .map(el => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Alpha', 'Zeta']);
  });

  it('blendet das Aktionsmenue fuer Nicht-Admins aus', () => {
    const fixture = setup(false, [device('A', 'ACTIVE')], bridge());

    expect(fixture.nativeElement.querySelector('.device-card .actions-toggle')).toBeNull();
    expect(fixture.nativeElement.querySelector('.permit-join')).toBeNull();
  });

  it('zeigt das Aktionsmenue und den Anlernknopf fuer Admins', () => {
    const fixture = setup(true, [device('A', 'ACTIVE')], bridge());

    expect(fixture.nativeElement.querySelector('.device-card .actions-toggle')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.permit-join')).not.toBeNull();
  });

  it('weist auf eine abgeschaltete Verfuegbarkeitspruefung hin', () => {
    const fixture = setup(false, [], bridge({ availabilityCheckEnabled: false }));

    expect(fixture.nativeElement.querySelector('.availability-hint')?.textContent).toContain('nicht aktiviert');
  });

  it('zeigt keinen Hinweis, solange die Bridge-Info fehlt', () => {
    const fixture = setup(false, [], bridge({ availabilityCheckEnabled: null }));

    expect(fixture.nativeElement.querySelector('.availability-hint')).toBeNull();
  });

  it('rechnet den Countdown aus permitJoinEnd des Servers', fakeAsync(() => {
    const end = new Date(Date.now() + 90_000).toISOString();
    const fixture = setup(true, [], bridge({ permitJoin: true, permitJoinEnd: end }));

    expect(fixture.componentInstance.permitJoinRemaining).toBeGreaterThanOrEqual(89);
    tick(2000);
    expect(fixture.componentInstance.permitJoinRemaining).toBeLessThanOrEqual(88);
    fixture.destroy();
  }));

  it('uebernimmt ein per SSE geoeffnetes Anlernfenster', fakeAsync(() => {
    const fixture = setup(true, [], bridge());
    bridgeInfo$.next(bridge({ permitJoin: true, permitJoinEnd: new Date(Date.now() + 60_000).toISOString() }));
    fixture.detectChanges();

    expect(fixture.componentInstance.permitJoinRemaining).toBeGreaterThan(0);
    fixture.destroy();
  }));

  describe('Umbenennen-Dialog', () => {
    it('laedt die Flow-Warnung beim Oeffnen und zeigt sie an', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      serviceSpy.getFlowReferences.and.returnValue(of([{ flowId: 4, name: 'Feuer-Verdacht', enabled: true }]));

      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.detectChanges();

      expect(serviceSpy.getFlowReferences).toHaveBeenCalledWith('A');
      expect(fixture.nativeElement.querySelector('.flow-warning')?.textContent).toContain('Feuer-Verdacht');
    });

    it('meldet einen gescheiterten Referenz-Abruf statt still gruen zu sein', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      serviceSpy.getFlowReferences.and.returnValue(throwError(() => new Error('down')));

      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.flow-warning')?.textContent).toContain('konnte nicht geprüft werden');
    });

    it('loest das Geraet beim Bestaetigen neu auf und tut nichts, wenn es fehlt', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.componentInstance.dialog!.newName = 'B';
      fixture.componentInstance.devices = [];

      fixture.componentInstance.confirmDialog();

      expect(serviceSpy.renameDevice).not.toHaveBeenCalled();
      expect(fixture.componentInstance.dialog).toBeNull();
    });

    it('benennt ueber die IEEE-Adresse um', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.componentInstance.dialog!.newName = 'Bewegung Büro';

      fixture.componentInstance.confirmDialog();

      expect(serviceSpy.renameDevice).toHaveBeenCalledWith('0xA', 'Bewegung Büro');
      expect(fixture.componentInstance.dialog).toBeNull();
    });
  });

  describe('Entfernen-Dialog', () => {
    it('bietet nach einem Fehlschlag das Erzwingen an', () => {
      const fixture = setup(true, [device('A', 'SILENT')], bridge());
      serviceSpy.removeDevice.and.returnValue(throwError(() => new Error('device is not responding')));
      fixture.componentInstance.openDialog('remove', fixture.componentInstance.devices[0]);

      fixture.componentInstance.confirmDialog();
      fixture.detectChanges();

      expect(serviceSpy.removeDevice).toHaveBeenCalledWith('0xA', false);
      expect(fixture.componentInstance.dialog?.error).toBe('device is not responding');
      expect(fixture.nativeElement.querySelector('.dialog__force')).not.toBeNull();

      serviceSpy.removeDevice.and.returnValue(of(void 0));
      fixture.componentInstance.confirmDialog(true);

      expect(serviceSpy.removeDevice).toHaveBeenCalledWith('0xA', true);
      expect(fixture.componentInstance.dialog).toBeNull();
    });
  });

  it('bietet fuer ein z2m-unbekanntes Geraet nur das lokale Entfernen an', () => {
    const fixture = setup(true, [device('Alt', 'SILENT', { knownToBridge: false, id: 9 })], bridge());

    const card = fixture.nativeElement.querySelector('.device-card');
    expect(card.classList).toContain('device-card--orphan');
    expect(card.querySelector('.actions-toggle')).toBeNull();
    expect(card.querySelector('.purge-button')).not.toBeNull();
  });

  it('purge loest das Geraet ueber die DB-Id auf', () => {
    const fixture = setup(true, [device('Alt', 'SILENT', { knownToBridge: false, id: 9 })], bridge());
    fixture.componentInstance.openDialog('purge', fixture.componentInstance.devices[0]);

    fixture.componentInstance.confirmDialog();

    expect(serviceSpy.purgeLocalData).toHaveBeenCalledWith(9);
  });

  it('laedt nach Live-Events hoechstens einmal je 5 s neu', fakeAsync(() => {
    const liveEvents$ = new Subject<ZigbeeLiveEvent>();
    const fixture = setup(true, [device('A', 'ACTIVE')], bridge(), liveEvents$.asObservable());
    const callsAfterSetup = serviceSpy.getDevices.calls.count();
    const event: ZigbeeLiveEvent = {
      friendlyName: 'A', measurementType: 'TEMPERATURE', value: 21, unit: '°C', measuredAt: new Date().toISOString()
    };

    liveEvents$.next(event);
    tick(200);
    liveEvents$.next(event);
    tick(200);
    liveEvents$.next(event);
    tick(5000);

    expect(serviceSpy.getDevices.calls.count()).toBe(callsAfterSetup + 1);
    fixture.destroy();
  }));

  it('setzt den Verlaufs-Sensor nach Umbenennen um', () => {
    const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
    const component = fixture.componentInstance;
    expect(component.selectedDevice).toBe('A');

    component.openDialog('rename', component.devices[0]);
    component.dialog!.newName = 'B';
    component.confirmDialog();

    expect(component.selectedDevice).toBe('B');
  });
});
