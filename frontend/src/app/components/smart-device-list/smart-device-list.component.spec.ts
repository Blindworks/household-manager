import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SmartDeviceListComponent } from './smart-device-list.component';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SmartDevice } from '../../models/smart-device.model';

describe('SmartDeviceListComponent', () => {
  let serviceSpy: jasmine.SpyObj<SmartDeviceService>;

  const device: SmartDevice = {
    id: 1, deviceType: 'TAPO', externalDeviceId: 'DEV1', deviceName: 'Stehlampe',
    model: 'L530E(EU)', ipAddress: '192.168.1.112', isOnline: true, isPoweredOn: false,
    capabilities: ['SWITCH'], metadata: {}, createdAt: '', updatedAt: ''
  } as SmartDevice;

  const bulbDevice: SmartDevice = {
    id: 2, deviceType: 'TAPO', externalDeviceId: 'DEV2', deviceName: 'Flur',
    model: 'L530(EU)', ipAddress: '192.168.1.114', isOnline: true, isPoweredOn: true,
    capabilities: ['SWITCH', 'BRIGHTNESS', 'COLOR', 'COLOR_TEMP'],
    metadata: { colorTempRangeMin: 2500, colorTempRangeMax: 6500 },
    createdAt: '', updatedAt: ''
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
});
