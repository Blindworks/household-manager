import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SmartDeviceListComponent } from './smart-device-list.component';
import { SmartDeviceService } from '../../services/smart-device.service';
import { LightStateRequest, SmartDevice } from '../../models/smart-device.model';
import { hueSaturationToHex } from '../../shared/color-conversion.util';

describe('SmartDeviceListComponent', () => {
  let serviceSpy: jasmine.SpyObj<SmartDeviceService>;

  const device: SmartDevice = {
    id: 1, deviceType: 'TAPO', externalDeviceId: 'DEV1', deviceName: 'Stehlampe',
    model: 'L530E(EU)', ipAddress: '192.168.1.112', isOnline: true, isPoweredOn: false,
    capabilities: ['SWITCH'], metadata: {}, createdAt: '', updatedAt: ''
  } as SmartDevice;

  // Faehigkeiten gemeldet, aber noch nie live geprobt - Backend liefert kein brightness/hue/
  // saturation/colorTemp-Feld (kein "null", das Feld fehlt komplett, siehe SmartDeviceResponse).
  const bulbDevice: SmartDevice = {
    id: 2, deviceType: 'TAPO', externalDeviceId: 'DEV2', deviceName: 'Flur',
    model: 'L530(EU)', ipAddress: '192.168.1.114', isOnline: true, isPoweredOn: true,
    capabilities: ['SWITCH', 'BRIGHTNESS', 'COLOR', 'COLOR_TEMP'],
    metadata: { colorTempRangeMin: 2500, colorTempRangeMax: 6500 },
    createdAt: '', updatedAt: ''
  } as SmartDevice;

  // Dieselbe Lampe, aber mit einem vom Geraet tatsaechlich gemeldeten Ist-Zustand.
  const bulbDeviceWithValues: SmartDevice = {
    ...bulbDevice,
    id: 3,
    brightness: 50, hue: 200, saturation: 80, colorTemp: 3000
  } as SmartDevice;

  beforeEach(async () => {
    localStorage.removeItem('smartDeviceViewMode');

    serviceSpy = jasmine.createSpyObj('SmartDeviceService', [
      'getAllDevices', 'scanDevices', 'refreshDeviceState', 'turnOn', 'turnOff', 'addKasaDeviceByIp',
      'setLightState', 'setTapoAddress'
    ]);
    serviceSpy.getAllDevices.and.returnValue(of([device]));
    serviceSpy.scanDevices.and.returnValue(of([device]));
    serviceSpy.turnOn.and.returnValue(of(void 0));
    serviceSpy.turnOff.and.returnValue(of(void 0));
    // loadDevices() schedules a 500ms background refresh via refreshDeviceState() for
    // every device - without a stub it returns undefined and .subscribe() throws once
    // that timer fires (can land mid-spec or during teardown and disconnect Chrome).
    serviceSpy.refreshDeviceState.and.returnValue(of(device));
    serviceSpy.setLightState.and.returnValue(of(bulbDevice));
    serviceSpy.setTapoAddress.and.returnValue(of(bulbDevice));

    await TestBed.configureTestingModule({
      imports: [SmartDeviceListComponent],
      providers: [{ provide: SmartDeviceService, useValue: serviceSpy }]
    }).compileComponents();
  });

  afterEach(() => {
    localStorage.removeItem('smartDeviceViewMode');
  });

  it('zeigt Geraete aus der DB an', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(serviceSpy.getAllDevices).toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Stehlampe');
  });

  it('stoesst den Rescan fuer einen Typ an', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.scanType('TAPO');

    expect(serviceSpy.scanDevices).toHaveBeenCalledWith('TAPO');
  });

  it('startet standardmaessig in der Normal-Ansicht', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.viewMode).toBe('normal');
  });

  it('speichert die gewaehlte Ansicht in localStorage', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.setViewMode('compact');

    expect(fixture.componentInstance.viewMode).toBe('compact');
    expect(localStorage.getItem('smartDeviceViewMode')).toBe('compact');
  });

  it('laedt die gespeicherte Ansicht beim Init', () => {
    localStorage.setItem('smartDeviceViewMode', 'compact');

    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.viewMode).toBe('compact');
  });

  it('schaltet das Geraet ueber die kompakte Karten-Flaeche', () => {
    localStorage.setItem('smartDeviceViewMode', 'compact');

    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    const hit = (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.device-card__hit');
    expect(hit).not.toBeNull();

    hit!.click();

    expect(serviceSpy.turnOn).toHaveBeenCalledWith(device.id);
  });

  it('zeigt das Kasa-per-IP-Formular nach Klick auf den Knopf', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.showAddKasaForm).toBeFalse();

    fixture.componentInstance.toggleAddKasaForm();

    expect(fixture.componentInstance.showAddKasaForm).toBeTrue();
  });

  it('lehnt eine ungueltige IP ab, ohne den Service aufzurufen', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.kasaIpInput = 'keine-ip';
    fixture.componentInstance.submitAddKasaForm();

    expect(serviceSpy.addKasaDeviceByIp).not.toHaveBeenCalled();
    expect(fixture.componentInstance.addKasaError).toContain('Ungueltige IP-Adresse');
  });

  it('lehnt ein Oktett mit fuehrender Null ab', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.kasaIpInput = '010.1.1.1';
    fixture.componentInstance.submitAddKasaForm();

    expect(serviceSpy.addKasaDeviceByIp).not.toHaveBeenCalled();
    expect(fixture.componentInstance.addKasaError).toContain('Ungueltige IP-Adresse');
  });

  it('fuegt ein Kasa-Geraet per IP hinzu und laedt die Liste neu', () => {
    serviceSpy.addKasaDeviceByIp.and.returnValue(of(device));

    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.kasaIpInput = '192.168.1.116';
    fixture.componentInstance.submitAddKasaForm();

    expect(serviceSpy.addKasaDeviceByIp).toHaveBeenCalledWith('192.168.1.116');
    expect(fixture.componentInstance.showAddKasaForm).toBeFalse();
    expect(fixture.componentInstance.addKasaSuccessMessage).toBeTruthy();
    expect(serviceSpy.getAllDevices).toHaveBeenCalledTimes(2);
  });

  it('behaelt die eingegebene IP bei einem Fehler und zeigt die Meldung an', () => {
    serviceSpy.addKasaDeviceByIp.and.returnValue(throwError(() => new Error('Das Geraet hat nicht geantwortet.')));

    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleAddKasaForm();
    fixture.componentInstance.kasaIpInput = '192.168.1.116';
    fixture.componentInstance.submitAddKasaForm();

    expect(fixture.componentInstance.addKasaError).toBe('Das Geraet hat nicht geantwortet.');
    expect(fixture.componentInstance.kasaIpInput).toBe('192.168.1.116');
    expect(fixture.componentInstance.showAddKasaForm).toBeTrue();
  });

  it('zeigt fuer eine reine Steckdose keine Licht-Regler', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasLightControls(device)).toBeFalse();
  });

  it('zeigt fuer eine Lampe mit Faehigkeiten Licht-Regler', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasLightControls(bulbDevice)).toBeTrue();
    expect(fixture.componentInstance.hasCapability(bulbDevice, 'BRIGHTNESS')).toBeTrue();
    expect(fixture.componentInstance.hasCapability(bulbDevice, 'COLOR_TEMP')).toBeTrue();
  });

  it('liest den geraetespezifischen Farbtemperatur-Bereich aus der Metadata', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.getColorTempRange(bulbDevice)).toEqual({ min: 2500, max: 6500 });
  });

  it('faellt ohne Metadata auf den Standard-Farbtemperatur-Bereich zurueck', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    const plainBulb = { ...bulbDevice, id: 3, metadata: {} } as SmartDevice;
    expect(fixture.componentInstance.getColorTempRange(plainBulb)).toEqual({ min: 2500, max: 6500 });
  });

  it('sendet die Helligkeit erst beim Loslassen (change), nicht bei jeder Bewegung (input)', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    const inputEvent = { target: { value: '42' } } as unknown as Event;
    fixture.componentInstance.onBrightnessInput(bulbDevice, inputEvent);

    expect(serviceSpy.setLightState).not.toHaveBeenCalled();
    expect(fixture.componentInstance.getLightState(bulbDevice).brightness).toBe(42);

    fixture.componentInstance.onBrightnessCommit(bulbDevice);

    expect(serviceSpy.setLightState).toHaveBeenCalledWith(bulbDevice.id, { brightness: 42 });
  });

  it('waehlt eine Farbe und sendet hue/saturation statt des Hex-Werts', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    const inputEvent = { target: { value: '#ff0000' } } as unknown as Event;
    fixture.componentInstance.onColorInput(bulbDevice, inputEvent);
    fixture.componentInstance.onColorCommit(bulbDevice);

    expect(serviceSpy.setLightState).toHaveBeenCalledWith(bulbDevice.id, { hue: 0, saturation: 100 });
  });

  it('behaelt den zuletzt gewaehlten Wert bei einem Fehler statt ihn zurueckzusetzen', () => {
    serviceSpy.setLightState.and.returnValue(throwError(() => new Error('Geraet meldet BRIGHTNESS nicht.')));

    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    const inputEvent = { target: { value: '77' } } as unknown as Event;
    fixture.componentInstance.onBrightnessInput(bulbDevice, inputEvent);
    fixture.componentInstance.onBrightnessCommit(bulbDevice);

    const state = fixture.componentInstance.getLightState(bulbDevice);
    expect(state.brightness).toBe(77);
    expect(state.error).toBe('Geraet meldet BRIGHTNESS nicht.');
    expect(state.pending).toBeFalse();
  });

  it('setzt die Tapo-Adresse und laedt die Liste danach neu', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleAddressForm(bulbDevice);
    fixture.componentInstance.onAddressInput(bulbDevice, { target: { value: '192.168.1.120' } } as unknown as Event);
    fixture.componentInstance.submitAddressForm(bulbDevice);

    expect(serviceSpy.setTapoAddress).toHaveBeenCalledWith(bulbDevice.id, '192.168.1.120');
    expect(fixture.componentInstance.getAddressForm(bulbDevice).visible).toBeFalse();
    expect(serviceSpy.getAllDevices).toHaveBeenCalledTimes(2);
  });

  it('lehnt eine ungueltige IP im Adressformular ab, ohne den Service aufzurufen', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleAddressForm(bulbDevice);
    fixture.componentInstance.onAddressInput(bulbDevice, { target: { value: '010.1.1.1' } } as unknown as Event);
    fixture.componentInstance.submitAddressForm(bulbDevice);

    expect(serviceSpy.setTapoAddress).not.toHaveBeenCalled();
    expect(fixture.componentInstance.getAddressForm(bulbDevice).error).toContain('Ungueltige IP-Adresse');
  });

  it('belegt die Regler beim Laden mit dem tatsaechlichen Ist-Zustand des Geraets vor', () => {
    serviceSpy.getAllDevices.and.returnValue(of([bulbDeviceWithValues]));

    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    const state = fixture.componentInstance.getLightState(bulbDeviceWithValues);
    expect(state.brightness).toBe(50);
    expect(state.brightnessKnown).toBeTrue();
    expect(state.colorTemp).toBe(3000);
    expect(state.colorTempKnown).toBeTrue();
    expect(state.colorHex).toBe(hueSaturationToHex(200, 80));
    expect(state.colorKnown).toBeTrue();
  });

  it('faellt fuer einen ungeprobten Wert auf einen Default zurueck und markiert ihn als unbekannt', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    const state = fixture.componentInstance.getLightState(bulbDevice);
    expect(state.brightness).toBe(100);
    expect(state.brightnessKnown).toBeFalse();
    expect(state.colorTemp).toBe(4500);
    expect(state.colorTempKnown).toBeFalse();
    expect(state.colorHex).toBe('#ffffff');
    expect(state.colorKnown).toBeFalse();
  });

  it('markiert einen Regler als bekannt, sobald der Nutzer ihn aktiv bedient', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.onBrightnessInput(bulbDevice, { target: { value: '30' } } as unknown as Event);

    expect(fixture.componentInstance.getLightState(bulbDevice).brightnessKnown).toBeTrue();
  });

  it('uebernimmt nach dem Setzen den vom Geraet bestaetigten Wert statt des optimistisch gesendeten', () => {
    const confirmedDevice: SmartDevice = { ...bulbDevice, brightness: 44 } as SmartDevice;
    serviceSpy.setLightState.and.returnValue(of(confirmedDevice));

    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.onBrightnessInput(bulbDevice, { target: { value: '42' } } as unknown as Event);
    fixture.componentInstance.onBrightnessCommit(bulbDevice);

    const state = fixture.componentInstance.getLightState(bulbDevice);
    expect(state.brightness).toBe(44);
    expect(state.brightnessKnown).toBeTrue();
  });

  it('sendet Farbe und Farbtemperatur nie gemeinsam in einem Request', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.onColorInput(bulbDevice, { target: { value: '#00ff00' } } as unknown as Event);
    fixture.componentInstance.onColorCommit(bulbDevice);

    fixture.componentInstance.onColorTempInput(bulbDevice, { target: { value: '4000' } } as unknown as Event);
    fixture.componentInstance.onColorTempCommit(bulbDevice);

    expect(serviceSpy.setLightState).toHaveBeenCalledTimes(2);
    for (const callArgs of serviceSpy.setLightState.calls.allArgs()) {
      const request = callArgs[1] as LightStateRequest;
      const hasColor = request.hue !== undefined || request.saturation !== undefined;
      const hasColorTemp = request.colorTemp !== undefined;
      expect(hasColor && hasColorTemp).toBeFalse();
    }
  });

  describe('Ausschalt-Bestaetigung', () => {
    // Frisches Objekt pro Test statt eines geteilten Consts: toggleDevice/executeToggle
    // mutiert device.isPoweredOn direkt auf der uebergebenen Referenz - ein geteiltes Objekt
    // wuerde bei zufaelliger (Jasmine-Standard) Testreihenfolge Zustand zwischen Tests durchsickern
    // lassen (z. B. "schaltet nach Bestaetigung aus" schaltet isPoweredOn auf false um, wodurch
    // ein danach laufender Dialog-Test die Wache faelschlich als nicht ausgeloest sieht).
    let guardedDevice: SmartDevice;

    beforeEach(() => {
      guardedDevice = {
        ...device, id: 9, deviceName: 'Kuehlschrank', isPoweredOn: true, confirmRequired: true
      } as SmartDevice;
      serviceSpy.getAllDevices.and.returnValue(of([guardedDevice]));
      serviceSpy.refreshDeviceState.and.returnValue(of(guardedDevice));
    });

    it('oeffnet beim Ausschalten den Dialog statt zu schalten', () => {
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();

      fixture.componentInstance.toggleDevice(guardedDevice);

      expect(serviceSpy.turnOff).not.toHaveBeenCalled();
      expect(fixture.componentInstance.confirmOffDevice?.id).toBe(9);
    });

    it('schaltet nach Bestaetigung aus und schliesst den Dialog', () => {
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();
      fixture.componentInstance.toggleDevice(guardedDevice);

      fixture.componentInstance.confirmTurnOff();

      expect(serviceSpy.turnOff).toHaveBeenCalledWith(9);
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });

    it('abbrechen schliesst den Dialog ohne zu schalten', () => {
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();
      fixture.componentInstance.toggleDevice(guardedDevice);

      fixture.componentInstance.closeConfirmOffDialog();

      expect(serviceSpy.turnOff).not.toHaveBeenCalled();
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });

    it('einschalten eines geschuetzten Geraets fragt nicht nach', () => {
      const offDevice = { ...guardedDevice, isPoweredOn: false } as SmartDevice;
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();

      fixture.componentInstance.toggleDevice(offDevice);

      expect(serviceSpy.turnOn).toHaveBeenCalledWith(9);
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });

    it('ungeschuetztes Geraet schaltet weiterhin direkt aus', () => {
      const plain = { ...device, isPoweredOn: true } as SmartDevice;
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();

      fixture.componentInstance.toggleDevice(plain);

      expect(serviceSpy.turnOff).toHaveBeenCalledWith(1);
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });
  });
});
