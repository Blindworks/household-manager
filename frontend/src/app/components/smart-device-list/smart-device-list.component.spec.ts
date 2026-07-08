import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
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

  beforeEach(async () => {
    localStorage.removeItem('smartDeviceViewMode');

    serviceSpy = jasmine.createSpyObj('SmartDeviceService', [
      'getAllDevices', 'scanDevices', 'refreshDeviceState', 'turnOn', 'turnOff'
    ]);
    serviceSpy.getAllDevices.and.returnValue(of([device]));
    serviceSpy.scanDevices.and.returnValue(of([device]));
    serviceSpy.turnOn.and.returnValue(of(void 0));
    serviceSpy.turnOff.and.returnValue(of(void 0));

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
});
