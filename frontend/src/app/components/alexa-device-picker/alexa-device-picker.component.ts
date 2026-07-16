import { Component, EventEmitter, OnInit, Output, computed, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AlexaService } from '../../services/alexa.service';
import { AlexaDevice } from '../../models/alexa.model';

/** Mehrfachauswahl für Alexa-Geräte. Wert = string[] von serialNumbers. */
@Component({
  selector: 'app-alexa-device-picker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './alexa-device-picker.component.html',
  styleUrl: './alexa-device-picker.component.scss'
})
export class AlexaDevicePickerComponent implements OnInit {
  private readonly alexaService = inject(AlexaService);

  readonly value = input<string[]>([]);
  @Output() valueChange = new EventEmitter<string[]>();

  readonly options = signal<AlexaDevice[]>([]);

  readonly selected = computed(() => new Set(this.value() ?? []));

  ngOnInit(): void {
    this.alexaService.getDevices().subscribe(list => this.options.set(list));
  }

  isSelected(serial: string): boolean {
    return this.selected().has(serial);
  }

  toggle(serial: string, checked: boolean): void {
    const next = new Set(this.value() ?? []);
    if (checked) { next.add(serial); } else { next.delete(serial); }
    this.valueChange.emit([...next]);
  }
}
