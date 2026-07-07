import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { DevicesComponent } from './devices.component';
import { SmartDeviceService } from '../../services/smart-device.service';

describe('DevicesComponent', () => {
  let serviceSpy: jasmine.SpyObj<SmartDeviceService>;

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('SmartDeviceService', [
      'getAllDevices', 'scanDevices', 'refreshDeviceState', 'turnOn', 'turnOff'
    ]);
    serviceSpy.getAllDevices.and.returnValue(of([]));
    serviceSpy.scanDevices.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DevicesComponent],
      providers: [{ provide: SmartDeviceService, useValue: serviceSpy }]
    }).compileComponents();
  });

  it('laedt beim Start nur die Geraeteliste aus der DB, ohne Scan', () => {
    const fixture = TestBed.createComponent(DevicesComponent);
    fixture.detectChanges(); // ngOnInit

    expect(serviceSpy.getAllDevices).toHaveBeenCalled();
    expect(serviceSpy.scanDevices).not.toHaveBeenCalled();
  });
});
