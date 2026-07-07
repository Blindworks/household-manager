import { Component } from '@angular/core';
import { SmartDeviceListComponent } from '../../components/smart-device-list/smart-device-list.component';

/**
 * User-facing smart device overview page.
 */
@Component({
  selector: 'app-devices',
  standalone: true,
  imports: [SmartDeviceListComponent],
  templateUrl: './devices.component.html',
  styleUrl: './devices.component.scss'
})
export class DevicesComponent {
}
