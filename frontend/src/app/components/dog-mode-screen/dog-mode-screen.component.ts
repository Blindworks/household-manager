import { Component, EventEmitter, Input, Output } from '@angular/core';
import { DogModeClimate, EMPTY_DOG_MODE_CLIMATE } from '../../shared/dog-mode.util';

/**
 * Vollbild-Screen „Toni allein" (Muster Tesla-Dog-Mode). Reine Anzeige: Uhr und Werte
 * kommen vom Dashboard, das Beenden meldet der Knopf per endMode zurueck.
 *
 * Eigene Styles statt lumina-Klassen — die sind in dashboard.component.scss gekapselt
 * und griffen hier lautlos nicht. Farben nur ueber die Theme-Tokens, die vom
 * .lumina-Wurzelelement per CSS-Vererbung ankommen (hell und dunkel).
 */
@Component({
  selector: 'app-dog-mode-screen',
  standalone: true,
  templateUrl: './dog-mode-screen.component.html',
  styleUrls: ['./dog-mode-screen.component.scss']
})
export class DogModeScreenComponent {
  @Input({ required: true }) clockTime = '';
  @Input({ required: true }) climate: DogModeClimate = EMPTY_DOG_MODE_CLIMATE;
  @Input() busy = false;
  @Input() error: string | null = null;
  @Output() endMode = new EventEmitter<void>();
}
