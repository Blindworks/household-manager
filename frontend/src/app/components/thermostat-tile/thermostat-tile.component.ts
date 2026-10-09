import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  OnInit,
  Output,
  SimpleChanges
} from '@angular/core';
import { Subject, Subscription, debounceTime } from 'rxjs';
import { Thermostat, ThermostatMode } from '../../models/thermostat.model';
import {
  ThermostatTone,
  arcFraction,
  formatTemp,
  stepTarget,
  thermostatTone
} from '../../shared/thermostat-view.util';

interface ModeOption {
  value: ThermostatMode;
  label: string;
  icon: string;
}

/** Geometrie des Rings im 200x200-Koordinatensystem: 270 Grad, Luecke unten. */
const RING_RADIUS = 78;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;
const RING_TRACK_LENGTH = RING_CIRCUMFERENCE * 0.75;
const RING_START_DEGREES = 135;
const RING_SWEEP_DEGREES = 270;

/**
 * Raumkachel eines Heizkoerperthermostats mit Ring-Zifferblatt.
 *
 * Reine Darstellung und Bedienung: der Server-Zustand kommt als Input, jede
 * Aenderung geht als Output hinaus. Tippen auf +/- springt sofort optimistisch
 * um (pendingTarget) und meldet erst 800 ms nach dem letzten Tipp EINEN Wert -
 * sonst wuerde jeder Tipp einen Funkbefehl an den Hub schicken.
 */
@Component({
  selector: 'app-thermostat-tile',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Default,
  templateUrl: './thermostat-tile.component.html',
  styleUrl: './thermostat-tile.component.scss'
})
export class ThermostatTileComponent implements OnInit, OnChanges, OnDestroy {
  private static readonly DEBOUNCE_MS = 800;

  @Input({ required: true }) thermostat!: Thermostat;
  /** Waehrend ein Schreibzugriff laeuft, ist die Bedienung gesperrt. */
  @Input() busy = false;
  /** Inline-Meldung zu einem fehlgeschlagenen Schreibzugriff. */
  @Input() errorMessage: string | null = null;

  @Output() readonly targetChange = new EventEmitter<number>();
  @Output() readonly modeChange = new EventEmitter<ThermostatMode>();

  readonly modes: readonly ModeOption[] = [
    { value: 'MANUAL', label: 'Manuell', icon: 'tune' },
    { value: 'FROST_PROTECTION', label: 'Frost', icon: 'ac_unit' },
    { value: 'OFF', label: 'Aus', icon: 'power_settings_new' }
  ];

  readonly circumference = RING_CIRCUMFERENCE;
  readonly trackLength = RING_TRACK_LENGTH;
  readonly ringRadius = RING_RADIUS;

  /** Lokal angetippter Sollwert, bis der Server-Wert nachzieht. */
  private pendingTarget: number | null = null;
  /** Lokal gewaehlter Modus, bis der Server-Zustand nachzieht. */
  private pendingMode: ThermostatMode | null = null;
  /** Zwischen erstem Tipp und Ablauf der Entprellzeit darf ein Server-Refresh die Anzeige nicht ueberschreiben. */
  private tapOutstanding = false;
  private readonly taps = new Subject<number>();
  private subscription: Subscription | null = null;

  ngOnInit(): void {
    this.subscription = this.taps
      .pipe(debounceTime(ThermostatTileComponent.DEBOUNCE_MS))
      .subscribe(value => this.commitTarget(value));
  }

  ngOnChanges(changes: SimpleChanges): void {
    const newError = changes['errorMessage']?.currentValue;
    // Fehlgeschlagenes Schreiben: zurueck auf den Serverwert, auch wenn kein frisches Objekt kommt.
    // Waehrend busy darf ein Refresh den lokalen Stand nicht ueberschreiben (kein Zuruecksprung).
    if (newError || (changes['thermostat'] && !this.tapOutstanding && !this.busy)) {
      this.pendingTarget = null;
      this.pendingMode = null;
    }
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.subscription = null;
  }

  get tone(): ThermostatTone {
    return thermostatTone(this.thermostat);
  }

  get controlsDisabled(): boolean {
    return !this.thermostat.online || this.busy;
  }

  get displayTarget(): number | null {
    return this.pendingTarget ?? this.thermostat.targetTemp;
  }

  get activeMode(): ThermostatMode {
    return this.pendingMode ?? this.thermostat.mode;
  }

  get currentText(): string {
    return formatTemp(this.thermostat.online ? this.thermostat.currentTemp : null);
  }

  get targetText(): string {
    return formatTemp(this.thermostat.online ? this.displayTarget : null);
  }

  get trackDash(): string {
    return `${RING_TRACK_LENGTH} ${RING_CIRCUMFERENCE}`;
  }

  /** Gefuellter Teil des Rings; ohne Wert (offline) bleibt nur die Schiene. */
  get fillDash(): string | null {
    const fraction = this.ringFraction;
    return fraction === null ? null : `${fraction * RING_TRACK_LENGTH} ${RING_CIRCUMFERENCE}`;
  }

  /** Position des Griffs am Ende des gefuellten Bogens. */
  get knob(): { x: number; y: number } | null {
    const fraction = this.ringFraction;
    if (fraction === null) {
      return null;
    }
    const radians = ((RING_START_DEGREES + fraction * RING_SWEEP_DEGREES) * Math.PI) / 180;
    return { x: 100 + RING_RADIUS * Math.cos(radians), y: 100 + RING_RADIUS * Math.sin(radians) };
  }

  get caption(): string {
    switch (this.tone) {
      case 'offline':
        return 'Keine Daten';
      case 'off':
        return 'Heizung aus';
      case 'frost':
        return 'Frostschutz';
      case 'heating':
        return 'Heizt';
      case 'idle':
        return 'Ziel erreicht';
    }
  }

  private get ringFraction(): number | null {
    return this.thermostat.online ? arcFraction(this.displayTarget) : null;
  }

  step(direction: 1 | -1): void {
    if (this.controlsDisabled) {
      return;
    }
    const start = this.displayTarget ?? this.thermostat.currentTemp ?? 20;
    const next = stepTarget(start, direction);
    if (next === (this.displayTarget ?? null)) {
      return;
    }
    this.pendingTarget = next;
    this.tapOutstanding = true;
    this.taps.next(next);
  }

  selectMode(mode: ThermostatMode): void {
    if (this.controlsDisabled || mode === this.activeMode) {
      return;
    }
    this.pendingMode = mode;
    this.modeChange.emit(mode);
  }

  private commitTarget(value: number): void {
    this.tapOutstanding = false;
    if (value === this.thermostat.targetTemp) {
      // Hoch und wieder runter getippt: kein Befehl noetig.
      this.pendingTarget = null;
      return;
    }
    this.targetChange.emit(value);
  }
}
