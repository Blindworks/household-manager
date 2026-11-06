import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, OnChanges, OnDestroy, Output,
  SimpleChanges, inject
} from '@angular/core';
import { ThermostatMode } from '../../../models/thermostat.model';
import { HouseDeviceControlService } from '../house-device-control.service';
import { TARGET_GRACE_MS, UNREACHABLE } from './detail-texts';

/**
 * Betriebsart eines Thermostats im Detail-Feld (Aus, Manuell, Frostschutz).
 *
 * Der Dienst liest den Modus frisch und schreibt nur bei Abweichung. Nach einem Schreiben
 * gilt der lokale Modus, bis das Thermostat ihn meldet oder die Schonfrist ablaeuft.
 */
@Component({
  selector: 'app-thermostat-mode-control',
  standalone: true,
  templateUrl: './thermostat-mode-control.component.html',
  styleUrl: './thermostat-mode-control.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ThermostatModeControlComponent implements OnChanges, OnDestroy {
  private readonly control = inject(HouseDeviceControlService);
  private readonly cdr = inject(ChangeDetectorRef);

  @Input({ required: true }) thermostatId!: number;
  @Input() mode: ThermostatMode | null = null;
  /** Das Schreiben hat den Modus geaendert oder gezeigt, dass der angezeigte Stand veraltet ist. */
  @Output() readonly changed = new EventEmitter<void>();

  readonly options: { mode: ThermostatMode; label: string }[] = [
    { mode: 'OFF', label: 'Aus' },
    { mode: 'MANUAL', label: 'Manuell' },
    { mode: 'FROST_PROTECTION', label: 'Frostschutz' }
  ];

  localMode: ThermostatMode | null = null;
  pending = false;
  error: string | null = null;
  private graceTimer: ReturnType<typeof setTimeout> | null = null;

  get shownMode(): ThermostatMode | null {
    return this.localMode ?? this.mode;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['thermostatId'] && !changes['thermostatId'].firstChange) {
      this.dropLocal();
      this.pending = false;
      this.error = null;
    } else if (changes['mode'] && this.localMode !== null && this.mode === this.localMode) {
      this.dropLocal();
    }
  }

  ngOnDestroy(): void {
    this.clearGrace();
  }

  select(mode: ThermostatMode): void {
    if (this.pending || mode === this.shownMode) {
      return;
    }
    const thermostatId = this.thermostatId;
    this.pending = true;
    this.error = null;
    this.control.setThermostatMode(thermostatId, mode).subscribe({
      next: outcome => {
        this.pending = false;
        if (outcome === 'unavailable') {
          this.error = UNREACHABLE;
        } else {
          if (outcome === 'done' && thermostatId === this.thermostatId) {
            this.localMode = mode;
            this.startGrace();
          }
          this.changed.emit();
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.pending = false;
        this.error = UNREACHABLE;
        this.cdr.markForCheck();
      }
    });
  }

  private startGrace(): void {
    this.clearGrace();
    this.graceTimer = setTimeout(() => {
      this.graceTimer = null;
      this.localMode = null;
      this.cdr.markForCheck();
    }, TARGET_GRACE_MS);
  }

  private clearGrace(): void {
    if (this.graceTimer !== null) {
      clearTimeout(this.graceTimer);
      this.graceTimer = null;
    }
  }

  private dropLocal(): void {
    this.localMode = null;
    this.clearGrace();
  }
}
