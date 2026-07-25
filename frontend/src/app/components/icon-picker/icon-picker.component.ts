import {
  Component,
  ElementRef,
  HostListener,
  inject,
  model,
  signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

/**
 * Visueller Auswahlknopf für ein Material-Symbol. Zeigt das aktuell gewählte
 * Icon an und öffnet ein Raster kuratierter Haushalts-Icons. Für Sonderfälle
 * kann der Symbolname zusätzlich frei eingegeben werden. Der Wert ist stets der
 * Material-Symbol-Name (z. B. "nights_stay"); leer bedeutet "kein Icon".
 */
@Component({
  selector: 'app-icon-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './icon-picker.component.html',
  styleUrl: './icon-picker.component.scss'
})
export class IconPickerComponent {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** Gewählter Material-Symbol-Name; leer = kein Icon. */
  readonly value = model<string>('');

  readonly open = signal<boolean>(false);

  /** Kuratierte Auswahl gängiger Haushalts- und Automatisierungs-Icons. */
  readonly icons: readonly string[] = [
    'lightbulb', 'nights_stay', 'bedtime', 'light_mode', 'wb_sunny', 'mode_fan',
    'home', 'meeting_room', 'door_front', 'lock', 'key', 'bolt',
    'power_settings_new', 'thermostat', 'ac_unit', 'water_drop', 'local_fire_department', 'humidity_percentage',
    'air', 'cloud', 'coffee', 'restaurant', 'bed', 'weekend',
    'tv', 'router', 'notifications', 'schedule', 'calendar_month', 'cleaning_services',
    'pets', 'child_care', 'directions_car', 'luggage', 'flight_takeoff', 'shopping_cart',
    'music_note', 'movie', 'sports_esports', 'fitness_center', 'work', 'exit_to_app',
    'favorite', 'star', 'flag', 'celebration', 'eco', 'shield'
  ];

  toggle(): void {
    this.open.update(v => !v);
  }

  select(icon: string): void {
    this.value.set(icon);
    this.open.set(false);
  }

  clear(): void {
    this.value.set('');
    this.open.set(false);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target)) {
      this.open.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.open.set(false);
  }
}
