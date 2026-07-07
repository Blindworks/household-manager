import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { DevicesComponent } from './devices.component';
import { SmartDeviceService } from '../../services/smart-device.service';

describe('DevicesComponent', () => {
  it('rendert die Geraeteliste', async () => {
    const serviceSpy = jasmine.createSpyObj('SmartDeviceService', ['getAllDevices', 'scanDevices']);
    serviceSpy.getAllDevices.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DevicesComponent],
      providers: [{ provide: SmartDeviceService, useValue: serviceSpy }]
    }).compileComponents();

    const fixture = TestBed.createComponent(DevicesComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-smart-device-list')).toBeTruthy();
    expect(serviceSpy.scanDevices).not.toHaveBeenCalled();
  });
});
