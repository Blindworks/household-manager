import { Component, EventEmitter, OnInit, Output, computed, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SmartDeviceService } from '../../../services/smart-device.service';
import { SmartDevice } from '../../../models/smart-device.model';

/** Dropdown für SmartDevice-Referenzen (Feldtyp DEVICE_REF). Wert = Geräte-ID (number). */
@Component({
  selector: 'app-device-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './device-picker.component.html'
})
export class DevicePickerComponent implements OnInit {
  private readonly deviceService = inject(SmartDeviceService);

  readonly value = input<number>();
  @Output() valueChange = new EventEmitter<number>();

  readonly options = signal<SmartDevice[]>([]);

  readonly displayLabel = computed(() => {
    const v = this.value();
    if (v === undefined || v === null) { return ''; }
    const found = this.options().find(o => o.id === v);
    return found ? found.deviceName : `nicht gefunden: ${v}`;
  });

  ngOnInit(): void {
    this.deviceService.getAllDevices().subscribe(list => this.options.set(list));
  }

  select(id: string): void {
    this.valueChange.emit(Number(id));
  }
}
