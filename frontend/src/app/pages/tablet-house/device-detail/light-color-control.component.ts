import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, Output, inject
} from '@angular/core';
import { LightExtras } from '../../../shared/house/detail-extras.util';
import {
  COLOR_SWATCHES, COLOR_TEMP_STEP_K, ColorSwatch, LightColorRequest, activeSwatch, sliderValue
} from '../../../shared/house/light-color.util';
import { HouseDeviceControlService } from '../house-device-control.service';
import { LAMP_OFF, NOT_APPLIED } from './detail-texts';

/**
 * Farbfelder und Farbtemperatur-Regler einer Lampe im Detail-Feld.
 *
 * Warmweiss ist ein Farbtemperatur-Wert: das Feld gibt es nur, wenn das Geraet zusaetzlich
 * COLOR_TEMP kann. Der Dienst liest den Lampenzustand frisch und schreibt nur bei `on`.
 */
@Component({
  selector: 'app-light-color-control',
  standalone: true,
  templateUrl: './light-color-control.component.html',
  styleUrl: './light-color-control.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LightColorControlComponent {
  private readonly control = inject(HouseDeviceControlService);
  private readonly cdr = inject(ChangeDetectorRef);

  @Input({ required: true }) entityId!: string;
  @Input({ required: true }) color!: LightExtras;
  @Input() lampOn: boolean | null = null;
  /** Das Schreiben hat den Lampenzustand veraendert oder gezeigt, dass der angezeigte Stand veraltet ist. */
  @Output() readonly changed = new EventEmitter<void>();

  pending = false;
  error: string | null = null;
  readonly stepK = COLOR_TEMP_STEP_K;

  get swatches(): readonly ColorSwatch[] {
    if (!this.color.supportsColor) {
      return [];
    }
    return COLOR_SWATCHES.filter(s => !('colorTemp' in s.request) || this.color.supportsColorTemp);
  }

  get active(): ColorSwatch['id'] | null {
    return activeSwatch(this.color);
  }

  get sliderMin(): number {
    return this.color.colorTempRange.min;
  }

  get sliderMax(): number {
    return this.color.colorTempRange.max;
  }

  get sliderPosition(): number {
    return sliderValue(this.color, this.color.colorTempRange);
  }

  get locked(): boolean {
    return this.lampOn !== true || this.pending;
  }

  pick(swatch: ColorSwatch): void {
    this.send(swatch.request);
  }

  onSlider(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    if (Number.isFinite(value)) {
      this.send({ colorTemp: value });
    }
  }

  private send(request: LightColorRequest): void {
    if (this.locked) {
      return;
    }
    this.pending = true;
    this.error = null;
    this.control.setLightColor(this.entityId, request).subscribe({
      next: outcome => {
        this.pending = false;
        if (outcome === 'lamp-off') {
          this.error = LAMP_OFF;
        }
        // Auch `lamp-off`: dann war der angezeigte Zustand „an“ veraltet.
        this.changed.emit();
        this.cdr.markForCheck();
      },
      error: () => {
        this.pending = false;
        this.error = NOT_APPLIED;
        this.cdr.markForCheck();
      }
    });
  }
}
