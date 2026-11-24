import { Component, EventEmitter, Input, OnDestroy, Output, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Observable } from 'rxjs';
import { CommunicatorCard, CommunicatorSuggestion } from '../../../models/communicator.model';
import { ModeService } from '../../../services/mode.service';
import { NukiService } from '../../../services/nuki.service';
import { SwitchService } from '../../../services/switch.service';
import { ThermostatService } from '../../../services/thermostat.service';

const MIN_TARGET = 5;
const MAX_TARGET = 30;
const TARGET_STEP = 0.5;
/** Mehrere Tipps auf +/− werden gesammelt und als EIN Sollwert geschrieben. */
const TARGET_DEBOUNCE_MS = 600;
const DEFAULT_TARGET = 20;

interface Switchable {
  entityId: string;
  displayName: string;
  state: string;
  confirmRequired?: boolean;
}

/**
 * Eine Antwort-Karte des Communicators samt ihrer Bedienung. Die Karte zeigt, was der Server
 * geliefert hat; geschaltet wird ueber die normalen Endpunkte (Thermostat, Schalter, Modus, Schloss),
 * damit Rechte und Audit dieselben sind wie ueberall sonst.
 */
@Component({
  selector: 'app-communicator-card',
  standalone: true,
  imports: [NgTemplateOutlet],
  templateUrl: './communicator-card.component.html',
  styleUrl: './communicator-card.component.scss'
})
export class CommunicatorCardComponent implements OnDestroy {
  private readonly thermostats = inject(ThermostatService);
  private readonly switches = inject(SwitchService);
  private readonly modes = inject(ModeService);
  private readonly nuki = inject(NukiService);

  /** Lokale Kopie: Aktionen aktualisieren die Anzeige, ohne die Antwort der Seite zu veraendern. */
  readonly view = signal<CommunicatorCard>({ type: 'info', title: '' });
  readonly busy = signal(false);
  readonly notice = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  /** Geraet mit Ausschalt-Bestaetigung: der erste Tipp fragt, erst der zweite schaltet. */
  readonly confirmingOff = signal(false);

  /** Folgefrage eines Vorschlags; die Seite stellt sie wie eine gesprochene Frage. */
  @Output() readonly ask = new EventEmitter<string>();

  private targetTimer: ReturnType<typeof setTimeout> | null = null;
  /** Letzter vom Server bestaetigter Sollwert, fuer das Zurueckspringen nach einem Fehler. */
  private confirmedTarget: number | undefined;

  @Input({ required: true }) set card(card: CommunicatorCard) {
    this.view.set({ ...card });
    this.confirmedTarget = card.targetTemp;
  }

  ngOnDestroy(): void {
    if (this.targetTimer) {
      clearTimeout(this.targetTimer);
    }
  }

  // --- Anzeige ---------------------------------------------------------------------------

  degrees(value: number | undefined): string {
    return value == null
      ? '–'
      : value.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  }

  isOn(): boolean {
    return this.view().state === 'on';
  }

  /** Alles ausser on/off (unavailable, unknown) ist nicht schaltbar — nie als „aus“ behandeln. */
  isSwitchable(): boolean {
    const state = this.view().state;
    return state === 'on' || state === 'off';
  }

  stateLabel(): string {
    const state = this.view().state;
    return state === 'on' ? 'An' : state === 'off' ? 'Aus' : 'Nicht erreichbar';
  }

  lockLabel(): string {
    switch (this.view().state) {
      case 'locked': return 'Verriegelt';
      case 'unlocked': return 'Entriegelt';
      case 'unlatched': return 'Falle gezogen';
      case 'locking': return 'Verriegelt gerade …';
      case 'unlocking': return 'Entriegelt gerade …';
      default: return 'Zustand unbekannt';
    }
  }

  doorLabel(): string | null {
    const door = this.view().doorState;
    return door === 'on' ? 'Tür offen' : door === 'off' ? 'Tür geschlossen' : null;
  }

  canLock(): boolean {
    return this.view().state !== 'locked' && this.view().state !== 'locking';
  }

  // --- Thermostat ------------------------------------------------------------------------

  adjustTarget(delta: number): void {
    const current = this.view().targetTemp ?? DEFAULT_TARGET;
    this.scheduleTarget(current + delta);
  }

  private scheduleTarget(value: number): void {
    const thermostatId = this.view().thermostatId;
    if (thermostatId == null) {
      return;
    }
    const target = this.snapTarget(value);
    this.view.update(card => ({ ...card, targetTemp: target }));
    this.error.set(null);
    if (this.targetTimer) {
      clearTimeout(this.targetTimer);
    }
    this.targetTimer = setTimeout(() => this.writeTarget(thermostatId, target), TARGET_DEBOUNCE_MS);
  }

  private snapTarget(value: number): number {
    const snapped = Math.round(value / TARGET_STEP) * TARGET_STEP;
    return Math.min(MAX_TARGET, Math.max(MIN_TARGET, snapped));
  }

  private writeTarget(thermostatId: number, target: number): void {
    this.targetTimer = null;
    this.thermostats.setTarget(thermostatId, target).subscribe({
      next: () => {
        if (this.view().thermostatId === thermostatId) {
          this.confirmedTarget = target;
        }
        this.notice.set(`Sollwert ${this.degrees(target)} °C gesetzt`);
      },
      error: () => {
        if (this.view().thermostatId === thermostatId) {
          this.view.update(card => ({ ...card, targetTemp: this.confirmedTarget }));
        }
        this.fail('Der Sollwert konnte nicht gesetzt werden.');
      }
    });
  }

  // --- Schalter, Modus, Schloss ----------------------------------------------------------

  toggleOwn(): void {
    const card = this.view();
    if (!card.entityId || this.busy()) {
      return;
    }
    const desired = this.isOn() ? 'off' : 'on';
    this.setState(card.type === 'mode' ? 'mode' : 'switch', card.entityId, desired, this.confirmingOff());
  }

  lock(): void {
    const smartlockId = this.view().smartlockId;
    if (smartlockId == null || this.busy()) {
      return;
    }
    this.begin();
    this.nuki.sendAction(smartlockId, 'LOCK').subscribe({
      next: () => {
        this.view.update(card => ({ ...card, state: 'locking' }));
        this.finish('Die Tür wird verriegelt');
      },
      error: () => this.fail('Verriegeln hat nicht geklappt.')
    });
  }

  runSuggestion(suggestion: CommunicatorSuggestion): void {
    if (this.busy()) {
      return;
    }
    switch (suggestion.kind) {
      case 'ask':
        if (suggestion.question) {
          this.ask.emit(suggestion.question);
        }
        break;
      case 'thermostat-target':
        if (suggestion.thermostatId != null && suggestion.value != null) {
          this.runTargetSuggestion(suggestion.thermostatId, suggestion.value);
        }
        break;
      case 'switch':
      case 'mode':
        if (suggestion.entityId && suggestion.state) {
          this.setState(suggestion.kind, suggestion.entityId, suggestion.state, false);
        }
        break;
    }
  }

  private runTargetSuggestion(thermostatId: number, value: number): void {
    if (this.view().thermostatId === thermostatId) {
      this.scheduleTarget(value);
      return;
    }
    this.begin();
    this.thermostats.setTarget(thermostatId, this.snapTarget(value)).subscribe({
      next: updated => this.finish(`${updated.name}: Sollwert ${this.degrees(value)} °C`),
      error: () => this.fail('Der Sollwert konnte nicht gesetzt werden.')
    });
  }

  /**
   * Schaltet gezielt auf `desired`. Die Endpunkte koennen nur umschalten, deshalb wird der Zustand
   * vorher FRISCH gelesen: die Karte kann Minuten alt sein, und ein Toggle auf einem veralteten
   * Stand schaltete in die falsche Richtung.
   */
  private setState(kind: 'switch' | 'mode', entityId: string, desired: 'on' | 'off', confirmed: boolean): void {
    this.begin();
    const list$: Observable<Switchable[]> =
      kind === 'mode' ? this.modes.getModes() : this.switches.getSwitches(undefined, 'all');
    list$.subscribe({
      next: list => {
        const current = list.find(entry => entry.entityId === entityId);
        if (!current || (current.state !== 'on' && current.state !== 'off')) {
          this.fail('Das Gerät ist gerade nicht erreichbar.');
          return;
        }
        if (current.state === desired) {
          this.applyState(entityId, desired);
          this.finish(`${current.displayName} ist bereits ${desired === 'on' ? 'an' : 'aus'}`);
          return;
        }
        if (desired === 'off' && current.confirmRequired && !confirmed) {
          this.applyState(entityId, current.state);
          this.busy.set(false);
          this.confirmingOff.set(true);
          return;
        }
        const toggle$: Observable<Switchable> =
          kind === 'mode' ? this.modes.toggle(entityId) : this.switches.toggle(entityId);
        toggle$.subscribe({
          next: updated => {
            this.applyState(entityId, updated.state);
            this.finish(`${updated.displayName} ist jetzt ${updated.state === 'on' ? 'an' : 'aus'}`);
          },
          error: () => this.fail('Schalten hat nicht geklappt.')
        });
      },
      error: () => this.fail('Der Zustand konnte nicht gelesen werden.')
    });
  }

  cancelConfirm(): void {
    this.confirmingOff.set(false);
  }

  private applyState(entityId: string, state: string): void {
    if (this.view().entityId === entityId) {
      this.view.update(card => ({ ...card, state }));
    }
  }

  private begin(): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
  }

  private finish(notice: string): void {
    this.busy.set(false);
    this.confirmingOff.set(false);
    this.notice.set(notice);
  }

  private fail(message: string): void {
    this.busy.set(false);
    this.confirmingOff.set(false);
    this.notice.set(null);
    this.error.set(message);
  }
}
