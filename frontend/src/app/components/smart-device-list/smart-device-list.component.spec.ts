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
    serviceSpy = jasmine.createSpyObj('SmartDeviceService', [
      'getAllDevices', 'scanDevices', 'refreshDeviceState', 'turnOn', 'turnOff'
    ]);
    serviceSpy.getAllDevices.and.returnValue(of([device]));
    serviceSpy.scanDevices.and.returnValue(of([device]));

    await TestBed.configureTestingModule({
      imports: [SmartDeviceListComponent],
      providers: [{ provide: SmartDeviceService, useValue: serviceSpy }]
    }).compileComponents();
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
});
